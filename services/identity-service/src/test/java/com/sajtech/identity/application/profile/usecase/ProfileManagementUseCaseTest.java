package com.sajtech.identity.application.profile.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.profile.*;
import com.sajtech.identity.application.profile.model.*;
import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import com.sajtech.identity.application.profile.service.ProfileFingerprintEncoder;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProfileManagementUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
  private final ProfileContactStore store = mock(ProfileContactStore.class);
  private final AuthenticationStore authentication = mock(AuthenticationStore.class);
  private final RefreshCredentialLookup lookup = mock(RefreshCredentialLookup.class);
  private final IntentFingerprintPort fingerprints = mock(IntentFingerprintPort.class);
  private final ChallengeSecretPort challenges = mock(ChallengeSecretPort.class);
  private final NotificationEscrowPort escrow = mock(NotificationEscrowPort.class);
  private ProfileManagementUseCase useCase;

  @BeforeEach
  void setUp() {
    TransactionRunner transactions =
        new TransactionRunner() {
          @Override
          public <T> T required(Supplier<T> work) {
            return work.get();
          }
        };
    when(fingerprints.digest(any())).thenReturn(new FingerprintDigest(new byte[32], "v1", "k1"));
    useCase =
        new ProfileManagementUseCase(
            store,
            authentication,
            lookup,
            new ContactCanonicalizer(),
            new ProfileCanonicalizer(),
            new ProfileFingerprintEncoder(),
            fingerprints,
            challenges,
            escrow,
            transactions,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void expiredOrRevokedSessionCannotReadProfileData() {
    when(lookup.lock(authentication, "refresh"))
        .thenReturn(Optional.of(session(NOW.minusSeconds(1), NOW.plusSeconds(60), NOW)));

    assertThatThrownBy(() -> useCase.contacts("refresh"))
        .isInstanceOfSatisfying(
            ProfileException.class,
            exception -> assertThat(exception.error()).isEqualTo(ProfileError.INVALID_SESSION));
    verifyNoInteractions(store);
  }

  @Test
  void incorrectContactCodeIsRecordedAndNeverVerifiesTheContact() {
    UUID userId = UUID.randomUUID();
    UUID contactId = UUID.randomUUID();
    UUID challengeId = UUID.randomUUID();
    when(lookup.lock(authentication, "refresh"))
        .thenReturn(Optional.of(session(userId, NOW.minusSeconds(30))));
    when(store.findCommand(any())).thenReturn(Optional.empty());
    when(store.tryInsertCommand(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
    when(store.lockLatestChallenge(userId, contactId))
        .thenReturn(
            Optional.of(
                new LockedContactChallenge(
                    challengeId,
                    contactId,
                    userId,
                    new byte[32],
                    "k1",
                    "ACTIVE",
                    0,
                    NOW.plusSeconds(600),
                    NOW.minusSeconds(60),
                    "EMAIL",
                    "person@example.com",
                    "en")));
    when(challenges.matches(challengeId, "12345678", new byte[32], "k1")).thenReturn(false);

    boolean verified = useCase.verifyContact("refresh", UUID.randomUUID(), contactId, "12345678");

    assertThat(verified).isFalse();
    verify(store).recordFailedProof(challengeId, 1, false, NOW);
    verify(store, never()).confirmContact(any(), any());
  }

  @Test
  void primaryChangeRequiresAuthenticationNoOlderThanFiveMinutes() {
    UUID userId = UUID.randomUUID();
    when(lookup.lock(authentication, "refresh"))
        .thenReturn(Optional.of(session(userId, NOW.minus(Duration.ofMinutes(5)).minusNanos(1))));

    assertThatThrownBy(() -> useCase.primary("refresh", UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ProfileException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ProfileError.RECENT_AUTHENTICATION_REQUIRED));
    verify(store, never()).setPrimary(any(), any(), any());
  }

  @Test
  void addContactCanonicalizesAndPersistsARealChallengeAndEncryptedOutbox() {
    UUID userId = UUID.randomUUID();
    when(lookup.lock(authentication, "refresh"))
        .thenReturn(Optional.of(session(userId, NOW.minusSeconds(30))));
    when(store.findCommand(any())).thenReturn(Optional.empty());
    when(store.tryInsertCommand(any(), any(), any(), any(), any(), any(), any())).thenReturn(true);
    when(challenges.generate(any()))
        .thenReturn(new GeneratedChallenge("12345678", new byte[32], "k1"));
    when(escrow.encrypt(any(), any(), any(), eq("12345678")))
        .thenReturn(new EncryptedHandoff("e1", new byte[12], new byte[32]));

    UUID contactId =
        useCase.addContact(
            "refresh", UUID.randomUUID(), "EMAIL", "Person@B\u00dcCHER.example", "en");

    var prepared = org.mockito.ArgumentCaptor.forClass(PreparedContactChallenge.class);
    verify(store).insertContactChallenge(eq(userId), prepared.capture());
    assertThat(prepared.getValue().contactId()).isEqualTo(contactId);
    assertThat(prepared.getValue().contact().canonicalValue())
        .isEqualTo("person@xn--bcher-kva.example");
    assertThat(prepared.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
  }

  @Test
  void resendRejectsAContactClaimedByAnotherLiveOwnerBeforeGeneratingASecret() {
    UUID userId = UUID.randomUUID();
    UUID contactId = UUID.randomUUID();
    when(lookup.lock(authentication, "refresh"))
        .thenReturn(Optional.of(session(userId, NOW.minusSeconds(30))));
    when(store.findCommand(any())).thenReturn(Optional.empty());
    when(store.lockLatestChallenge(userId, contactId))
        .thenReturn(
            Optional.of(
                new LockedContactChallenge(
                    UUID.randomUUID(),
                    contactId,
                    userId,
                    new byte[32],
                    "k1",
                    "ACTIVE",
                    0,
                    NOW.minusSeconds(1),
                    NOW.minusSeconds(61),
                    "EMAIL",
                    "person@example.com",
                    "en")));
    when(store.contactKeyUnavailable(any(), eq(contactId), eq(NOW))).thenReturn(true);

    assertThatThrownBy(
            () -> useCase.resendContactVerification("refresh", UUID.randomUUID(), contactId))
        .isInstanceOfSatisfying(
            ProfileException.class,
            exception -> assertThat(exception.error()).isEqualTo(ProfileError.CONTACT_CONFLICT));
    verifyNoInteractions(challenges, escrow);
    verify(store, never()).replaceChallenge(any(), any());
  }

  private static LockedRefreshCredential session(UUID userId, Instant authenticatedAt) {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "session",
        userId,
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        authenticatedAt,
        NOW.plus(Duration.ofDays(1)),
        NOW.plus(Duration.ofDays(7)));
  }

  private static LockedRefreshCredential session(
      Instant idleExpiresAt, Instant absoluteExpiresAt, Instant authenticatedAt) {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "session",
        UUID.randomUUID(),
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        authenticatedAt,
        idleExpiresAt,
        absoluteExpiresAt);
  }
}
