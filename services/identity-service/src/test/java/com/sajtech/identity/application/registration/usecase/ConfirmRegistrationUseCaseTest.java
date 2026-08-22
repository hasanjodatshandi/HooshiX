package com.sajtech.identity.application.registration.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.model.ConfirmRegistrationCommand;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.model.GeneratedChallenge;
import com.sajtech.identity.application.registration.model.LockedChallenge;
import com.sajtech.identity.application.registration.model.PreparedChallengeReplacement;
import com.sajtech.identity.application.registration.model.PreparedRegistration;
import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.application.registration.model.ReservationRecord;
import com.sajtech.identity.application.registration.port.out.ChallengeSecretPort;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.registration.port.out.RegistrationStore;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.FingerprintMaterialEncoder;
import com.sajtech.identity.application.registration.service.IdempotencyGuard;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ConfirmRegistrationUseCaseTest {
  @Test
  void validProofConsumesQuotaConfirmsAndClaimsConfirmedDedup() {
    Instant now = Instant.parse("2026-08-18T00:00:00Z");
    UUID challengeId = UUID.randomUUID();
    TrackingStore store = new TrackingStore(UUID.randomUUID(), UUID.randomUUID(), challengeId, now);
    TrackingQuota quota = new TrackingQuota();
    IntentFingerprintPort fingerprints =
        new IntentFingerprintPort() {
          @Override
          public FingerprintDigest digest(byte[] material) {
            return new FingerprintDigest(new byte[32], "v1", "k1");
          }

          @Override
          public boolean matches(byte[] material, CommandDedupRecord stored) {
            return true;
          }
        };
    ChallengeSecretPort challenges =
        new ChallengeSecretPort() {
          @Override
          public GeneratedChallenge generate(UUID id) {
            throw new UnsupportedOperationException();
          }

          @Override
          public boolean matches(UUID id, String code, byte[] storedVerifier, String keyId) {
            assertThat(id).isEqualTo(challengeId);
            assertThat(code).isEqualTo("12345678");
            return true;
          }
        };
    ConfirmRegistrationUseCase useCase =
        new ConfirmRegistrationUseCase(
            new ContactCanonicalizer(),
            new FingerprintMaterialEncoder(),
            fingerprints,
            new IdempotencyGuard(fingerprints),
            quota,
            challenges,
            new DirectTransactionRunner(),
            store,
            Clock.fixed(now, ZoneOffset.UTC));

    boolean confirmed =
        useCase.confirm(
            new ConfirmRegistrationCommand(
                UUID.randomUUID(),
                RegistrationChannel.EMAIL,
                "Person@Example.com",
                "12345678",
                new byte[] {(byte) 203, 0, 113, 8}));

    assertThat(confirmed).isTrue();
    assertThat(quota.calls).isEqualTo(1);
    assertThat(store.confirmed).isTrue();
    assertThat(store.dedupOutcome).isEqualTo("CONFIRMED");
  }

  @Test
  void malformedProofStillConsumesQuotaAndCountsAgainstChallengeAttemptLimit() {
    Instant now = Instant.parse("2026-08-18T00:00:00Z");
    UUID challengeId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID contactId = UUID.randomUUID();
    TrackingStore store = new TrackingStore(userId, contactId, challengeId, now);
    TrackingQuota quota = new TrackingQuota();
    IntentFingerprintPort fingerprints =
        new IntentFingerprintPort() {
          @Override
          public FingerprintDigest digest(byte[] material) {
            return new FingerprintDigest(new byte[32], "v1", "k1");
          }

          @Override
          public boolean matches(byte[] material, CommandDedupRecord stored) {
            return true;
          }
        };
    ChallengeSecretPort challenges =
        new ChallengeSecretPort() {
          @Override
          public GeneratedChallenge generate(UUID id) {
            throw new UnsupportedOperationException();
          }

          @Override
          public boolean matches(UUID id, String code, byte[] storedVerifier, String keyId) {
            assertThat(code).isEqualTo("bad-code");
            return false;
          }
        };
    ConfirmRegistrationUseCase useCase =
        new ConfirmRegistrationUseCase(
            new ContactCanonicalizer(),
            new FingerprintMaterialEncoder(),
            fingerprints,
            new IdempotencyGuard(fingerprints),
            quota,
            challenges,
            new DirectTransactionRunner(),
            store,
            Clock.fixed(now, ZoneOffset.UTC));

    boolean confirmed =
        useCase.confirm(
            new ConfirmRegistrationCommand(
                UUID.randomUUID(),
                RegistrationChannel.EMAIL,
                "Person@Example.com",
                "bad-code",
                new byte[] {(byte) 203, 0, 113, 7}));

    assertThat(confirmed).isFalse();
    assertThat(quota.calls).isEqualTo(1);
    assertThat(store.failedAttempts).isEqualTo(1);
    assertThat(store.dedupOutcome).isEqualTo("REJECTED_PROOF");
    assertThat(store.confirmed).isFalse();
  }

  private static final class TrackingQuota implements SemanticQuotaPort {
    private int calls;

    @Override
    public void consume(QuotaRequest request) {
      calls++;
      assertThat(request.operation()).isEqualTo(QuotaOperation.CONFIRM_REGISTRATION);
    }
  }

  private static final class DirectTransactionRunner implements TransactionRunner {
    @Override
    public <T> T required(Supplier<T> work) {
      return work.get();
    }
  }

  private static final class TrackingStore implements RegistrationStore {
    private final ReservationRecord reservation;
    private final LockedChallenge challenge;
    private int failedAttempts;
    private String dedupOutcome;
    private boolean confirmed;

    TrackingStore(UUID userId, UUID contactId, UUID challengeId, Instant now) {
      reservation =
          new ReservationRecord(
              userId,
              contactId,
              challengeId,
              RegistrationLocale.EN,
              "Person@Example.com",
              now.plusSeconds(600),
              now);
      challenge =
          new LockedChallenge(
              userId,
              contactId,
              challengeId,
              new byte[32],
              "k1",
              0,
              now.plusSeconds(600),
              "ACTIVE");
    }

    @Override
    public Optional<CommandDedupRecord> findDedup(UUID requestId) {
      return Optional.empty();
    }

    @Override
    public void lockContactKey(CanonicalContact contact) {}

    @Override
    public boolean verifiedContactExists(CanonicalContact contact) {
      return false;
    }

    @Override
    public Optional<ReservationRecord> findReservation(CanonicalContact contact) {
      return Optional.of(reservation);
    }

    @Override
    public Optional<LockedChallenge> lockChallenge(UUID challengeId) {
      return Optional.of(challenge);
    }

    @Override
    public void expireChallenge(UUID challengeId, Instant now) {}

    @Override
    public void insertRegistration(PreparedRegistration registration) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void replaceChallenge(
        CanonicalContact contact,
        ReservationRecord reservation,
        PreparedChallengeReplacement replacement) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void recordFailedProof(
        UUID challengeId, int failedAttempts, boolean exhausted, Instant now) {
      this.failedAttempts = failedAttempts;
    }

    @Override
    public void confirm(UUID userId, UUID contactId, UUID challengeId, Instant now) {
      confirmed = true;
    }

    @Override
    public boolean tryInsertDedup(
        UUID requestId,
        String operation,
        byte[] fingerprint,
        String fingerprintVersion,
        String fingerprintKeyId,
        String outcome,
        Instant now) {
      dedupOutcome = outcome;
      return true;
    }

    @Override
    public int deleteDedupBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
