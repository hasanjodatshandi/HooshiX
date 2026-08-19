package com.sajtech.identity.application.authentication.usecase;

import static org.assertj.core.api.Assertions.assertThat;

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

class LogoutCurrentUseCaseTest {
  @Test
  void rotatedPredecessorPresentedToLogoutIsRecordedAsReuse() {
    RefreshDigest digest = new RefreshDigest("k1", "refresh-hmac-v1", new byte[32]);
    FakeStore store = new FakeStore();
    store.current =
        new LockedRefreshCredential(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "s".repeat(43),
            UUID.randomUUID(),
            "ROTATED",
            "ACTIVE",
            "ACTIVE",
            AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
            Instant.parse("2026-08-19T00:00:00Z"),
            Instant.parse("2026-09-18T00:00:00Z"));
    SessionCredentialPort credentials = new FixedCredentials(digest);
    LogoutCurrentUseCase useCase =
        new LogoutCurrentUseCase(
            new RefreshCredentialLookup(credentials),
            new DirectTransactionRunner(),
            store,
            Clock.fixed(Instant.parse("2026-08-18T12:00:00Z"), ZoneOffset.UTC));

    useCase.logout(new LogoutCurrentCommand(UUID.randomUUID(), FixedCredentials.RAW));

    assertThat(store.reason).isEqualTo(RefreshFamilyRevocationReason.REFRESH_REUSE);
  }

  private static final class FixedCredentials implements SessionCredentialPort {
    private static final String RAW = "r".repeat(43);
    private final RefreshDigest digest;

    private FixedCredentials(RefreshDigest digest) {
      this.digest = digest;
    }

    @Override
    public String newSessionId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RefreshDigest> digestCandidates(String encodedCredential) {
      if (!RAW.equals(encodedCredential)) throw new IllegalArgumentException();
      return List.of(digest);
    }
  }

  private static final class DirectTransactionRunner implements TransactionRunner {
    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      return work.get();
    }
  }

  private static final class FakeStore implements AuthenticationStore {
    private LockedRefreshCredential current;
    private RefreshFamilyRevocationReason reason;

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
      throw new UnsupportedOperationException();
    }

    @Override
    public void revokeFamily(
        UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now) {
      this.reason = reason;
    }

    @Override
    public void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now) {
      this.reason = reason;
    }

    @Override
    public int deleteFamiliesBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
