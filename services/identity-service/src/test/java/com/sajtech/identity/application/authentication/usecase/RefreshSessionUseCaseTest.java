package com.sajtech.identity.application.authentication.usecase;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshSessionUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
  private final FakeCredentials credentials = new FakeCredentials();
  private final FakeStore store = new FakeStore();
  private final TrackingTransactionRunner transactions = new TrackingTransactionRunner();
  private final RefreshSessionUseCase useCase =
      new RefreshSessionUseCase(
          new RefreshCredentialLookup(credentials),
          credentials,
          transactions,
          store,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void activeCredentialRotatesAndPreservesFamilyAndAbsoluteExpiry() {
    UUID family = UUID.randomUUID();
    String sessionId = "s".repeat(43);
    store.current =
        locked(family, sessionId, "ACTIVE", "ACTIVE", "ACTIVE", NOW.plus(Duration.ofDays(30)));

    AuthenticationSession result =
        useCase.refresh(
            new RefreshSessionCommand(UUID.randomUUID(), FakeCredentials.CURRENT_CREDENTIAL));

    assertThat(result.refreshFamilyId()).isEqualTo(family);
    assertThat(result.sessionId()).isEqualTo(sessionId);
    assertThat(result.refreshCredential()).isEqualTo(FakeCredentials.NEXT_CREDENTIAL);
    assertThat(result.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(result.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(store.rotated).isTrue();
  }

  @Test
  void rotatedPredecessorRevokesWholeFamilyInsideTransactionBeforeReuseErrorEscapes() {
    UUID family = UUID.randomUUID();
    store.current =
        locked(
            family, "s".repeat(43), "ROTATED", "ACTIVE", "ACTIVE", NOW.plus(Duration.ofDays(30)));

    assertThatThrownBy(
            () ->
                useCase.refresh(
                    new RefreshSessionCommand(
                        UUID.randomUUID(), FakeCredentials.CURRENT_CREDENTIAL)))
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(AuthenticationError.REFRESH_REUSE_DETECTED));

    assertThat(transactions.completed).isTrue();
    assertThat(store.revokedFamily).isEqualTo(family);
    assertThat(store.revocationReason).isEqualTo(RefreshFamilyRevocationReason.REFRESH_REUSE);
  }

  @Test
  void inactiveUserRevokesAllFamiliesAndRejectsRefresh() {
    UUID user = UUID.randomUUID();
    store.current =
        new LockedRefreshCredential(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "s".repeat(43),
            user,
            "ACTIVE",
            "ACTIVE",
            "SUSPENDED",
            AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
            NOW.plus(Duration.ofDays(1)),
            NOW.plus(Duration.ofDays(30)));

    assertThatThrownBy(
            () ->
                useCase.refresh(
                    new RefreshSessionCommand(
                        UUID.randomUUID(), FakeCredentials.CURRENT_CREDENTIAL)))
        .isInstanceOf(AuthenticationException.class);

    assertThat(store.revokedAllUser).isEqualTo(user);
    assertThat(store.revocationReason).isEqualTo(RefreshFamilyRevocationReason.USER_INACTIVE);
  }

  private static LockedRefreshCredential locked(
      UUID family,
      String sessionId,
      String credentialState,
      String familyState,
      String userStatus,
      Instant absoluteExpiry) {
    return new LockedRefreshCredential(
        UUID.randomUUID(),
        family,
        sessionId,
        UUID.randomUUID(),
        credentialState,
        familyState,
        userStatus,
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        NOW.plus(Duration.ofDays(1)),
        absoluteExpiry);
  }

  private static final class FakeCredentials implements SessionCredentialPort {
    private static final String CURRENT_CREDENTIAL = "c".repeat(43);
    private static final String NEXT_CREDENTIAL = "n".repeat(43);
    private static final RefreshDigest CURRENT_DIGEST =
        new RefreshDigest("k1", "refresh-hmac-v1", new byte[32]);

    @Override
    public String newSessionId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      byte[] next = new byte[32];
      next[0] = 1;
      return new GeneratedRefreshCredential(
          NEXT_CREDENTIAL, new RefreshDigest("k1", "refresh-hmac-v1", next));
    }

    @Override
    public List<RefreshDigest> digestCandidates(String encodedCredential) {
      if (!CURRENT_CREDENTIAL.equals(encodedCredential)) throw new IllegalArgumentException();
      return List.of(CURRENT_DIGEST);
    }
  }

  private static final class TrackingTransactionRunner implements TransactionRunner {
    private boolean completed;

    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      T value = work.get();
      completed = true;
      return value;
    }
  }

  private static final class FakeStore implements AuthenticationStore {
    private LockedRefreshCredential current;
    private boolean rotated;
    private UUID revokedFamily;
    private UUID revokedAllUser;
    private RefreshFamilyRevocationReason revocationReason;

    @Override
    public Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact) {
      return Optional.empty();
    }

    @Override
    public Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
        UUID userId, CanonicalContact contact) {
      return Optional.empty();
    }

    @Override
    public void expireDueFamilies(UUID userId, Instant now) {}

    @Override
    public int countActiveFamilies(UUID userId) {
      return 0;
    }

    @Override
    public Optional<UUID> oldestActiveFamily(UUID userId) {
      return Optional.empty();
    }

    @Override
    public void createSession(PreparedSession session) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest) {
      return Optional.ofNullable(current);
    }

    @Override
    public void rotateRefresh(
        LockedRefreshCredential current,
        UUID newCredentialId,
        RefreshDigest nextDigest,
        Instant now,
        Instant nextIdleExpiresAt) {
      rotated = true;
    }

    @Override
    public void revokeFamily(
        UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now) {
      revokedFamily = refreshFamilyId;
      revocationReason = reason;
    }

    @Override
    public void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now) {
      revokedAllUser = userId;
      revocationReason = reason;
    }

    @Override
    public int deleteFamiliesBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
