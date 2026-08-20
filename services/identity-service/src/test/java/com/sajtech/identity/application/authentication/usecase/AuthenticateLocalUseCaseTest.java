package com.sajtech.identity.application.authentication.usecase;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticateLocalUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
  private final FakeQuota quota = new FakeQuota();
  private final FakeVerifier verifier = new FakeVerifier();
  private final FakeStore store = new FakeStore();
  private final AuthenticateLocalUseCase useCase =
      new AuthenticateLocalUseCase(
          new ContactCanonicalizer(),
          new PasswordNormalizer(),
          quota,
          verifier,
          new FakeCredentials(),
          new DirectTransactionRunner(),
          store,
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void unknownVerifiedContactStillRunsPasswordProofAndChargesPostFailurePressure() {
    verifier.result = false;

    assertThatThrownBy(() -> useCase.authenticate(command()))
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(AuthenticationError.INVALID_CREDENTIALS));

    assertThat(quota.sourceChecks).isEqualTo(1);
    assertThat(quota.failures).isEqualTo(1);
    assertThat(quota.successes).isZero();
    assertThat(verifier.calls).isEqualTo(1);
    assertThat(verifier.lastEncodedHash).isNull();
    assertThat(store.created).isNull();
  }

  @Test
  void suspendedUserWithCorrectPasswordDoesNotCreateSessionAndUsesSameCredentialError() {
    UUID userId = UUID.randomUUID();
    store.local = new LocalCredentialRecord(userId, "SUSPENDED", "$argon2id$stored");
    verifier.result = true;

    assertThatThrownBy(() -> useCase.authenticate(command()))
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(AuthenticationError.INVALID_CREDENTIALS));

    assertThat(quota.failures).isZero();
    assertThat(quota.successes).isEqualTo(1);
    assertThat(store.created).isNull();
  }

  @Test
  void contactRemovedAfterPasswordProofIsRejectedBeforeSessionCommit() {
    UUID userId = UUID.randomUUID();
    store.local = new LocalCredentialRecord(userId, "ACTIVE", "$argon2id$stored");
    store.contactAvailableAtLock = false;
    verifier.result = true;

    assertThatThrownBy(() -> useCase.authenticate(command()))
        .isInstanceOfSatisfying(
            AuthenticationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(AuthenticationError.INVALID_CREDENTIALS));

    assertThat(store.created).isNull();
  }

  @Test
  void successfulLoginCreatesOnboardingFamilyWithExactIdleAndAbsoluteLifetimes() {
    UUID userId = UUID.randomUUID();
    store.local = new LocalCredentialRecord(userId, "ACTIVE", "$argon2id$stored");
    verifier.result = true;

    AuthenticationSession session = useCase.authenticate(command());

    assertThat(session.mode()).isEqualTo(AuthenticationSessionMode.AUTHENTICATED_ONBOARDING);
    assertThat(session.sessionId()).hasSize(43);
    assertThat(session.refreshCredential()).hasSize(43);
    assertThat(session.idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(session.absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(store.created.userId()).isEqualTo(userId);
    assertThat(store.created.idleExpiresAt()).isEqualTo(session.idleExpiresAt());
    assertThat(store.created.absoluteExpiresAt()).isEqualTo(session.absoluteExpiresAt());
    assertThat(quota.successes).isEqualTo(1);
  }

  @Test
  void creatingTwentyFirstFamilyRevokesDeterministicOldestBeforeInsert() {
    UUID userId = UUID.randomUUID();
    UUID oldest = UUID.randomUUID();
    store.local = new LocalCredentialRecord(userId, "ACTIVE", "$argon2id$stored");
    store.activeFamilies = 20;
    store.oldest = oldest;
    verifier.result = true;

    useCase.authenticate(command());

    assertThat(store.revokedFamily).isEqualTo(oldest);
    assertThat(store.revocationReason).isEqualTo(RefreshFamilyRevocationReason.ACTIVE_FAMILY_LIMIT);
    assertThat(store.created).isNotNull();
  }

  private static AuthenticateLocalCommand command() {
    return new AuthenticateLocalCommand(
        UUID.fromString("123e4567-e89b-42d3-a456-426614174000"),
        RegistrationChannel.EMAIL,
        "Person@Example.com",
        "correct password",
        new byte[] {127, 0, 0, 1});
  }

  private static final class FakeQuota implements LoginQuotaPort {
    private int sourceChecks;
    private int failures;
    private int successes;

    @Override
    public void checkSource(byte[] clientAddress) {
      sourceChecks++;
    }

    @Override
    public void recordFailure(CanonicalContact contact) {
      failures++;
    }

    @Override
    public void recordSuccess(CanonicalContact contact) {
      successes++;
    }
  }

  private static final class FakeVerifier implements PasswordVerificationPort {
    private int calls;
    private String lastEncodedHash;
    private boolean result;

    @Override
    public boolean matches(String normalizedPassword, String encodedHash) {
      calls++;
      lastEncodedHash = encodedHash;
      return result;
    }
  }

  @Test
  void successfulLoginAutoSelectsResolvedTenantContext() {
    UUID userId = UUID.randomUUID(), tenantId = UUID.randomUUID(), membershipId = UUID.randomUUID();
    store.local = new LocalCredentialRecord(userId, "ACTIVE", "$argon2id$stored");
    verifier.result = true;
    AuthenticateLocalUseCase tenantAware =
        new AuthenticateLocalUseCase(
            new ContactCanonicalizer(),
            new PasswordNormalizer(),
            quota,
            verifier,
            new FakeCredentials(),
            new DirectTransactionRunner(),
            store,
            ignored -> AuthenticationTenantSelection.tenant(tenantId, membershipId),
            Clock.fixed(NOW, ZoneOffset.UTC));

    AuthenticationSession session = tenantAware.authenticate(command());

    assertThat(session.mode()).isEqualTo(AuthenticationSessionMode.TENANT_AUTHENTICATED);
    assertThat(session.selectedTenantId()).isEqualTo(tenantId);
    assertThat(session.selectedMembershipId()).isEqualTo(membershipId);
    assertThat(store.created.mode()).isEqualTo(AuthenticationSessionMode.TENANT_AUTHENTICATED);
    assertThat(store.created.selectedTenantId()).isEqualTo(tenantId);
    assertThat(store.created.selectedMembershipId()).isEqualTo(membershipId);
  }

  private static final class FakeCredentials implements SessionCredentialPort {
    @Override
    public String newSessionId() {
      return "s".repeat(43);
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      return new GeneratedRefreshCredential(
          "r".repeat(43), new RefreshDigest("k1", "refresh-hmac-v1", new byte[32]));
    }

    @Override
    public java.util.List<RefreshDigest> digestCandidates(String encodedCredential) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class DirectTransactionRunner implements TransactionRunner {
    @Override
    public <T> T required(java.util.function.Supplier<T> work) {
      return work.get();
    }
  }

  private static final class FakeStore implements AuthenticationStore {
    private LocalCredentialRecord local;
    private boolean contactAvailableAtLock = true;
    private int activeFamilies;
    private UUID oldest;
    private PreparedSession created;
    private UUID revokedFamily;
    private RefreshFamilyRevocationReason revocationReason;

    @Override
    public Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact) {
      return Optional.ofNullable(local);
    }

    @Override
    public Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
        UUID userId, CanonicalContact contact) {
      return contactAvailableAtLock ? Optional.ofNullable(local) : Optional.empty();
    }

    @Override
    public void expireDueFamilies(UUID userId, Instant now) {}

    @Override
    public int countActiveFamilies(UUID userId) {
      return activeFamilies;
    }

    @Override
    public Optional<UUID> oldestActiveFamily(UUID userId) {
      return Optional.ofNullable(oldest);
    }

    @Override
    public void createSession(PreparedSession session) {
      created = session;
    }

    @Override
    public Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest) {
      return Optional.empty();
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
      revokedFamily = refreshFamilyId;
      revocationReason = reason;
      if (activeFamilies > 0) activeFamilies--;
    }

    @Override
    public void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int deleteFamiliesBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
