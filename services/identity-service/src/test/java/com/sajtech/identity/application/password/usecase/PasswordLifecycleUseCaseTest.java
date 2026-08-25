package com.sajtech.identity.application.password.usecase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.password.PasswordError;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.model.GeneratedRecoveryProof;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordLifecycleUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String NEXT_PASSWORD = "a-secure-next-password";

  @Test
  void changeChecksRemotePasswordOutsideTransactionAndRotatesOnlyCurrentFamily() {
    TrackingTransactions transactions = new TrackingTransactions();
    AuthenticationStore store = mock(AuthenticationStore.class);
    SessionCredentialPort credentials = mock(SessionCredentialPort.class);
    PasswordVerificationPort verifier = mock(PasswordVerificationPort.class);
    PasswordHashPort hashes = mock(PasswordHashPort.class);
    CompromisedPasswordPort compromised =
        password -> assertThat(transactions.inTransaction).isFalse();
    UUID userId = UUID.randomUUID();
    UUID familyId = UUID.randomUUID();
    RefreshDigest currentDigest = new RefreshDigest("k1", "v1", new byte[32]);
    RefreshDigest nextDigest = new RefreshDigest("k1", "v1", new byte[32]);
    LockedRefreshCredential session = session(userId, familyId, NOW.minusSeconds(30));
    LocalCredentialRecord local = new LocalCredentialRecord(userId, "ACTIVE", "$old");
    when(credentials.digestCandidates("refresh")).thenReturn(List.of(currentDigest));
    when(credentials.newRefreshCredential())
        .thenReturn(new GeneratedRefreshCredential("rotated-refresh", nextDigest));
    when(store.findRefreshCredential(currentDigest)).thenReturn(Optional.of(session));
    when(store.lockRefreshCredential(currentDigest)).thenReturn(Optional.of(session));
    when(store.findLocalCredential(userId)).thenReturn(Optional.of(local));
    when(store.lockLocalCredential(userId)).thenReturn(Optional.of(local));
    when(verifier.matches("current password", "$old")).thenReturn(true);
    when(hashes.hash(NEXT_PASSWORD))
        .thenAnswer(
            ignored -> {
              assertThat(transactions.inTransaction).isFalse();
              return "$next";
            });

    PasswordChangeSession result =
        new ChangePasswordUseCase(
                store,
                credentials,
                verifier,
                hashes,
                compromised,
                new PasswordNormalizer(),
                transactions,
                CLOCK)
            .change(
                new ChangePasswordCommand(
                    UUID.randomUUID(), "refresh", "current password", NEXT_PASSWORD));

    assertThat(result.refreshCredential()).isEqualTo("rotated-refresh");
    assertThat(result.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    verify(store).updatePasswordHash(userId, "$next", NOW);
    verify(store).rotateRefresh(eq(session), any(UUID.class), eq(nextDigest), eq(NOW), any());
    verify(store)
        .revokeOtherFamilies(userId, familyId, RefreshFamilyRevocationReason.PASSWORD_CHANGED, NOW);
    verify(store, never()).revokeAllFamilies(any(), any(), any());
    assertThat(transactions.calls).isEqualTo(1);
  }

  @Test
  void changeRejectsAStalePrimaryAuthenticationBeforeRemoteChecks() {
    AuthenticationStore store = mock(AuthenticationStore.class);
    SessionCredentialPort credentials = mock(SessionCredentialPort.class);
    RefreshDigest digest = new RefreshDigest("k1", "v1", new byte[32]);
    when(credentials.digestCandidates("refresh")).thenReturn(List.of(digest));
    when(store.findRefreshCredential(digest))
        .thenReturn(
            Optional.of(session(UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(301))));
    CompromisedPasswordPort compromised = mock(CompromisedPasswordPort.class);

    assertThatThrownBy(
            () ->
                new ChangePasswordUseCase(
                        store,
                        credentials,
                        mock(PasswordVerificationPort.class),
                        mock(PasswordHashPort.class),
                        compromised,
                        new PasswordNormalizer(),
                        new TrackingTransactions(),
                        CLOCK)
                    .change(
                        new ChangePasswordCommand(
                            UUID.randomUUID(), "refresh", "current password", NEXT_PASSWORD)))
        .isInstanceOfSatisfying(
            PasswordException.class,
            error ->
                assertThat(error.error()).isEqualTo(PasswordError.RECENT_AUTHENTICATION_REQUIRED));
    verifyNoInteractions(compromised);
  }

  @Test
  void recoveryRequestCreatesPurposeSeparatedChallengeAndEncryptedOutboxAtomically() {
    TrackingTransactions transactions = new TrackingTransactions();
    PasswordRecoveryStore store = mock(PasswordRecoveryStore.class);
    PasswordRecoverySecretPort secrets = mock(PasswordRecoverySecretPort.class);
    NotificationEscrowPort escrow = mock(NotificationEscrowPort.class);
    CanonicalContact contact =
        new CanonicalContact(RegistrationChannel.EMAIL, "person@example.com", "Person@example.com");
    UUID userId = UUID.randomUUID();
    UUID contactId = UUID.randomUUID();
    var target = new PasswordRecoveryStore.RecoveryTarget(userId, contactId, contact);
    when(store.findTargetByContact(any())).thenReturn(Optional.of(target));
    when(store.lockTargetByContact(any())).thenReturn(Optional.of(target));
    when(store.lockActiveByContact("person@example.com", NOW)).thenReturn(Optional.empty());
    when(secrets.generate(any()))
        .thenReturn(new GeneratedRecoveryProof("12345678", new byte[32], "recovery-k1"));
    when(escrow.encrypt(any(), eq(contact), eq(RegistrationLocale.FA), eq("12345678")))
        .thenReturn(new EncryptedHandoff("escrow-k1", new byte[12], new byte[32]));

    new RequestPasswordRecoveryUseCase(
            store,
            secrets,
            escrow,
            new ContactCanonicalizer(),
            ignored -> assertThat(transactions.inTransaction).isFalse(),
            transactions,
            CLOCK)
        .request(
            new RequestPasswordRecoveryCommand(
                UUID.randomUUID(),
                RegistrationChannel.EMAIL,
                "Person@example.com",
                new byte[] {127, 0, 0, 1}));

    ArgumentCaptor<PreparedPasswordRecovery> prepared =
        ArgumentCaptor.forClass(PreparedPasswordRecovery.class);
    verify(store).create(prepared.capture());
    assertThat(prepared.getValue().userId()).isEqualTo(userId);
    assertThat(prepared.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    assertThat(prepared.getValue().verifierKeyId()).isEqualTo("recovery-k1");
    assertThat(transactions.calls).isEqualTo(1);
  }

  @Test
  void recoveryRequestEnforcesSixtySecondChallengeSpacingInsideTransaction() {
    PasswordRecoveryStore store = mock(PasswordRecoveryStore.class);
    CanonicalContact contact =
        new CanonicalContact(RegistrationChannel.EMAIL, "person@example.com", "person@example.com");
    var target =
        new PasswordRecoveryStore.RecoveryTarget(UUID.randomUUID(), UUID.randomUUID(), contact);
    when(store.findTargetByContact(any())).thenReturn(Optional.of(target));
    when(store.lockTargetByContact(any())).thenReturn(Optional.of(target));
    when(store.lockActiveByContact("person@example.com", NOW))
        .thenReturn(
            Optional.of(
                new PasswordRecoveryStore.RecoveryChallenge(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new byte[32],
                    "k1",
                    NOW.plusSeconds(570),
                    "ACTIVE",
                    0)));
    PasswordRecoverySecretPort secrets = mock(PasswordRecoverySecretPort.class);
    when(secrets.generate(any()))
        .thenReturn(new GeneratedRecoveryProof("12345678", new byte[32], "k1"));
    NotificationEscrowPort escrow = mock(NotificationEscrowPort.class);
    when(escrow.encrypt(any(), any(), any(), any()))
        .thenReturn(new EncryptedHandoff("k1", new byte[12], new byte[32]));

    new RequestPasswordRecoveryUseCase(
            store,
            secrets,
            escrow,
            new ContactCanonicalizer(),
            ignored -> {},
            new TrackingTransactions(),
            CLOCK)
        .request(
            new RequestPasswordRecoveryCommand(
                UUID.randomUUID(),
                RegistrationChannel.EMAIL,
                "person@example.com",
                new byte[] {127, 0, 0, 1}));

    verify(store, never()).create(any());
  }

  @Test
  void validRecoveryProofChangesHashAndRevokesEveryRefreshFamilyAtomically() {
    TrackingTransactions transactions = new TrackingTransactions();
    PasswordRecoveryStore recovery = mock(PasswordRecoveryStore.class);
    PasswordRecoverySecretPort secrets = mock(PasswordRecoverySecretPort.class);
    AuthenticationStore authentication = mock(AuthenticationStore.class);
    PasswordHashPort hashes = mock(PasswordHashPort.class);
    UUID challengeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    var challenge =
        new PasswordRecoveryStore.RecoveryChallenge(
            challengeId, userId, new byte[32], "k1", NOW.plusSeconds(300), "ACTIVE", 0);
    when(recovery.findActiveByContact("person@example.com", NOW))
        .thenReturn(Optional.of(challenge));
    when(recovery.lockActiveByContact("person@example.com", NOW))
        .thenReturn(Optional.of(challenge));
    when(recovery.lockTargetByContact(any()))
        .thenReturn(
            Optional.of(
                new PasswordRecoveryStore.RecoveryTarget(
                    userId,
                    UUID.randomUUID(),
                    new CanonicalContact(
                        RegistrationChannel.EMAIL, "person@example.com", "person@example.com"))));
    when(secrets.matches(eq(challengeId), eq("12345678"), any(byte[].class), eq("k1")))
        .thenReturn(true);
    when(hashes.hash(NEXT_PASSWORD))
        .thenAnswer(
            ignored -> {
              assertThat(transactions.inTransaction).isFalse();
              return "$next";
            });
    UUID requestId = UUID.randomUUID();

    new ConfirmPasswordRecoveryUseCase(
            recovery,
            secrets,
            authentication,
            hashes,
            password -> assertThat(transactions.inTransaction).isFalse(),
            new PasswordNormalizer(),
            new ContactCanonicalizer(),
            ignored -> assertThat(transactions.inTransaction).isFalse(),
            transactions,
            CLOCK)
        .confirm(
            new ConfirmPasswordRecoveryCommand(
                requestId,
                RegistrationChannel.EMAIL,
                "person@example.com",
                "12345678",
                NEXT_PASSWORD,
                new byte[] {127, 0, 0, 1}));

    verify(authentication).updatePasswordHash(userId, "$next", NOW);
    verify(authentication)
        .revokeAllFamilies(userId, RefreshFamilyRevocationReason.PASSWORD_CHANGED, NOW);
    verify(recovery).markUsed(challengeId, requestId, NOW);
    assertThat(transactions.calls).isEqualTo(1);
  }

  private static LockedRefreshCredential session(
      UUID userId, UUID familyId, Instant authenticatedAt) {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        familyId,
        "session",
        userId,
        "ACTIVE",
        "ACTIVE",
        "ACTIVE",
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        authenticatedAt,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }

  private static final class TrackingTransactions implements TransactionRunner {
    private boolean inTransaction;
    private int calls;

    @Override
    public <T> T required(Supplier<T> work) {
      assertThat(inTransaction).isFalse();
      calls++;
      inTransaction = true;
      try {
        return work.get();
      } finally {
        inTransaction = false;
      }
    }
  }
}
