package com.sajtech.identity.application.registration.usecase;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.application.registration.model.GeneratedChallenge;
import com.sajtech.identity.application.registration.model.LockedChallenge;
import com.sajtech.identity.application.registration.model.PreparedChallengeReplacement;
import com.sajtech.identity.application.registration.model.PreparedRegistration;
import com.sajtech.identity.application.registration.model.RegisterLocalCommand;
import com.sajtech.identity.application.registration.model.ReservationRecord;
import com.sajtech.identity.application.registration.port.out.ChallengeSecretPort;
import com.sajtech.identity.application.registration.port.out.CompromisedPasswordPort;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import com.sajtech.identity.application.registration.port.out.RegistrationStore;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.FingerprintMaterialEncoder;
import com.sajtech.identity.application.registration.service.IdempotencyGuard;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.registration.service.ProfileCanonicalizer;
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

class RegisterLocalUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

  @Test
  void remoteSecurityCheckAndHashRunOutsideDatabaseTransactionsAndOutboxCommitsLocally() {
    TrackingTransactions tx = new TrackingTransactions();
    FakeStore store = new FakeStore();

    useCase(tx, store).register(command("550e8400-e29b-41d4-a716-446655440000"));

    assertThat(store.inserted).isNotNull();
    assertThat(store.inserted.contact().canonicalValue()).isEqualTo("person@example.com");
    assertThat(store.dedupClaimedBeforeInsert).isTrue();
    assertThat(tx.calls).isEqualTo(2);
  }

  @Test
  void expiredReservationCanBeReacquiredWithoutRevivingTheOldChallenge() {
    TrackingTransactions tx = new TrackingTransactions();
    UUID oldChallenge = UUID.randomUUID();
    FakeStore store =
        new FakeStore(
            new ReservationRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                oldChallenge,
                RegistrationLocale.EN,
                "Person@Example.com",
                NOW.minusSeconds(1),
                NOW.minusSeconds(61)));

    useCase(tx, store).register(command("650e8400-e29b-41d4-a716-446655440000"));

    assertThat(store.expiredChallenge).isEqualTo(oldChallenge);
    assertThat(store.inserted).isNotNull();
    assertThat(store.inserted.challengeId()).isNotEqualTo(oldChallenge);
    assertThat(store.dedupClaimedBeforeInsert).isTrue();
  }

  @Test
  void passwordLengthPolicyIsEnforcedBeforeQuotaOrPersistenceWork() {
    TrackingTransactions tx = new TrackingTransactions();
    FakeStore store = new FakeStore();
    RegisterLocalCommand valid = command("750e8400-e29b-41d4-a716-446655440000");
    RegisterLocalCommand weak =
        new RegisterLocalCommand(
            valid.requestId(),
            valid.channel(),
            valid.contact(),
            "short",
            valid.locale(),
            valid.firstName(),
            valid.lastName(),
            valid.fatherName(),
            valid.clientAddress());

    assertThatThrownBy(() -> useCase(tx, store).register(weak))
        .isInstanceOf(RuntimeException.class);
    assertThat(tx.calls).isZero();
    assertThat(store.inserted).isNull();
  }

  private static RegisterLocalUseCase useCase(TrackingTransactions tx, FakeStore store) {
    CompromisedPasswordPort compromised = password -> assertThat(tx.inTransaction).isFalse();
    PasswordHashPort hash =
        password -> {
          assertThat(tx.inTransaction).isFalse();
          return "$argon2id$test";
        };
    SemanticQuotaPort quota = request -> assertThat(tx.inTransaction).isFalse();
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
          public GeneratedChallenge generate(UUID challengeId) {
            return new GeneratedChallenge("12345678", new byte[32], "k1");
          }

          @Override
          public boolean matches(UUID challengeId, String code, byte[] verifier, String keyId) {
            return true;
          }
        };
    NotificationEscrowPort escrow =
        new NotificationEscrowPort() {
          @Override
          public EncryptedHandoff encrypt(
              UUID outboxId, CanonicalContact contact, RegistrationLocale locale, String code) {
            return new EncryptedHandoff("k1", new byte[12], new byte[32]);
          }

          @Override
          public DecryptedHandoff decrypt(
              UUID outboxId, String keyId, byte[] nonce, byte[] ciphertext) {
            throw new UnsupportedOperationException();
          }
        };
    return new RegisterLocalUseCase(
        false,
        new ContactCanonicalizer(),
        new ProfileCanonicalizer(),
        new PasswordNormalizer(),
        new FingerprintMaterialEncoder(),
        fingerprints,
        new IdempotencyGuard(fingerprints),
        quota,
        compromised,
        hash,
        challenges,
        escrow,
        tx,
        store,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static RegisterLocalCommand command(String requestId) {
    return new RegisterLocalCommand(
        UUID.fromString(requestId),
        RegistrationChannel.EMAIL,
        "Person@Example.com",
        "Strong password",
        RegistrationLocale.EN,
        "First",
        "Last",
        null,
        new byte[] {1, 2, 3, 4});
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

  private static final class FakeStore implements RegistrationStore {
    private final ReservationRecord reservation;
    private PreparedRegistration inserted;
    private UUID expiredChallenge;
    private boolean claimed;
    private boolean dedupClaimedBeforeInsert;

    FakeStore() {
      this(null);
    }

    FakeStore(ReservationRecord reservation) {
      this.reservation = reservation;
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
      return Optional.ofNullable(reservation);
    }

    @Override
    public Optional<LockedChallenge> lockChallenge(UUID challengeId) {
      return Optional.empty();
    }

    @Override
    public void expireChallenge(UUID challengeId, Instant now) {
      expiredChallenge = challengeId;
    }

    @Override
    public void insertRegistration(PreparedRegistration registration) {
      inserted = registration;
      dedupClaimedBeforeInsert = claimed;
    }

    @Override
    public void replaceChallenge(
        CanonicalContact contact,
        ReservationRecord reservation,
        PreparedChallengeReplacement replacement) {}

    @Override
    public void recordFailedProof(
        UUID challengeId, int failedAttempts, boolean exhausted, Instant now) {}

    @Override
    public void confirm(UUID userId, UUID contactId, UUID challengeId, Instant now) {}

    @Override
    public boolean tryInsertDedup(
        UUID requestId,
        String operation,
        byte[] fingerprint,
        String fingerprintVersion,
        String fingerprintKeyId,
        String outcome,
        Instant now) {
      claimed = true;
      return true;
    }

    @Override
    public int deleteDedupBefore(Instant cutoff, int batch) {
      return 0;
    }
  }
}
