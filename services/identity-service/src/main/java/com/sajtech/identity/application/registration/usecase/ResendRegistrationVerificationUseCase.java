package com.sajtech.identity.application.registration.usecase;

import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.in.ResendRegistrationVerification;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class ResendRegistrationVerificationUseCase implements ResendRegistrationVerification {
  private static final String OPERATION = "RESEND_REGISTRATION_VERIFICATION";
  private static final Duration RESEND_GAP = Duration.ofSeconds(60);
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
  private final ContactCanonicalizer contacts;
  private final FingerprintMaterialEncoder encoder;
  private final IntentFingerprintPort fingerprints;
  private final IdempotencyGuard idempotency;
  private final SemanticQuotaPort quota;
  private final ChallengeSecretPort challenges;
  private final NotificationEscrowPort escrow;
  private final TransactionRunner transactions;
  private final RegistrationStore store;
  private final Clock clock;

  public ResendRegistrationVerificationUseCase(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner transactions,
      RegistrationStore store,
      Clock clock) {
    this.contacts = contacts;
    this.encoder = encoder;
    this.fingerprints = fingerprints;
    this.idempotency = idempotency;
    this.quota = quota;
    this.challenges = challenges;
    this.escrow = escrow;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void resend(ResendRegistrationCommand command) {
    CanonicalContact contact = contacts.canonicalize(command.channel(), command.contact());
    byte[] material = encoder.resend(contact);
    try {
      Optional<CommandDedupRecord> replay = store.findDedup(command.requestId());
      if (replay.isPresent()) {
        idempotency.requireEqual(material, OPERATION, replay.get());
        return;
      }
      quota.consume(
          new QuotaRequest(
              QuotaOperation.RESEND_REGISTRATION_VERIFICATION, contact, command.clientAddress()));
      ReservationRecord eligible =
          transactions.required(() -> findEligible(command.requestId(), material, contact));
      if (eligible == null) return;
      Instant now = clock.instant();
      UUID newChallengeId = UUID.randomUUID();
      GeneratedChallenge generated = challenges.generate(newChallengeId);
      UUID outboxId = UUID.randomUUID();
      CanonicalContact persistedContact =
          new CanonicalContact(
              contact.channel(), contact.canonicalValue(), eligible.deliveryValue());
      EncryptedHandoff handoff =
          escrow.encrypt(outboxId, persistedContact, eligible.locale(), generated.code());
      PreparedChallengeReplacement replacement =
          new PreparedChallengeReplacement(
              eligible.challengeId(),
              newChallengeId,
              outboxId,
              UUID.randomUUID(),
              generated.verifier(),
              generated.keyId(),
              handoff,
              now,
              now.plus(CHALLENGE_TTL));
      FingerprintDigest fp = fingerprints.digest(material);
      transactions.required(
          () -> {
            commit(command.requestId(), material, contact, eligible, replacement, fp);
            return null;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private ReservationRecord findEligible(
      UUID requestId, byte[] material, CanonicalContact contact) {
    store.lockContactKey(contact);
    Optional<CommandDedupRecord> replay = store.findDedup(requestId);
    if (replay.isPresent()) {
      idempotency.requireEqual(material, OPERATION, replay.get());
      return null;
    }
    Instant now = clock.instant();
    Optional<ReservationRecord> reservation = store.findReservation(contact);
    if (store.verifiedContactExists(contact)
        || reservation.isEmpty()
        || !reservation.get().expiresAt().isAfter(now)
        || reservation.get().lastSentAt().plus(RESEND_GAP).isAfter(now)) {
      claimDedup(requestId, material, fingerprints.digest(material), now);
      return null;
    }
    return reservation.get();
  }

  private void commit(
      UUID requestId,
      byte[] material,
      CanonicalContact contact,
      ReservationRecord expected,
      PreparedChallengeReplacement replacement,
      FingerprintDigest fp) {
    store.lockContactKey(contact);
    Optional<CommandDedupRecord> replay = store.findDedup(requestId);
    if (replay.isPresent()) {
      idempotency.requireEqual(material, OPERATION, replay.get());
      return;
    }
    Instant now = clock.instant();
    Optional<ReservationRecord> current = store.findReservation(contact);
    if (current.isEmpty()
        || !current.get().challengeId().equals(expected.challengeId())
        || !current.get().expiresAt().isAfter(now)
        || current.get().lastSentAt().plus(RESEND_GAP).isAfter(now)) {
      claimDedup(requestId, material, fp, now);
      return;
    }
    if (!store.tryInsertDedup(
        requestId, OPERATION, fp.value(), fp.version(), fp.keyId(), "ACCEPTED", now)) {
      idempotency.requireEqual(material, OPERATION, store.findDedup(requestId).orElseThrow());
      return;
    }
    store.replaceChallenge(contact, current.get(), replacement);
  }

  private void claimDedup(UUID requestId, byte[] material, FingerprintDigest fp, Instant now) {
    if (!store.tryInsertDedup(
        requestId, OPERATION, fp.value(), fp.version(), fp.keyId(), "ACCEPTED", now)) {
      idempotency.requireEqual(material, OPERATION, store.findDedup(requestId).orElseThrow());
    }
  }
}
