package com.sajtech.identity.application.registration.usecase;

import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.in.ConfirmRegistration;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class ConfirmRegistrationUseCase implements ConfirmRegistration {
  private static final String OPERATION = "CONFIRM_REGISTRATION";
  private final ContactCanonicalizer contacts;
  private final FingerprintMaterialEncoder encoder;
  private final IntentFingerprintPort fingerprints;
  private final IdempotencyGuard idempotency;
  private final SemanticQuotaPort quota;
  private final ChallengeSecretPort challenges;
  private final TransactionRunner transactions;
  private final RegistrationStore store;
  private final Clock clock;

  public ConfirmRegistrationUseCase(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      ChallengeSecretPort challenges,
      TransactionRunner transactions,
      RegistrationStore store,
      Clock clock) {
    this.contacts = contacts;
    this.encoder = encoder;
    this.fingerprints = fingerprints;
    this.idempotency = idempotency;
    this.quota = quota;
    this.challenges = challenges;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public boolean confirm(ConfirmRegistrationCommand command) {
    CanonicalContact contact = contacts.canonicalize(command.channel(), command.contact());
    byte[] material = encoder.confirm(contact, command.code());
    try {
      Optional<CommandDedupRecord> replay = store.findDedup(command.requestId());
      if (replay.isPresent())
        return "CONFIRMED".equals(idempotency.requireEqual(material, OPERATION, replay.get()));
      quota.consume(
          new QuotaRequest(QuotaOperation.CONFIRM_REGISTRATION, contact, command.clientAddress()));
      return transactions.required(
          () -> confirmLocked(command.requestId(), material, contact, command.code()));
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private boolean confirmLocked(
      UUID requestId, byte[] material, CanonicalContact contact, String code) {
    store.lockContactKey(contact);
    Optional<CommandDedupRecord> replay = store.findDedup(requestId);
    if (replay.isPresent())
      return "CONFIRMED".equals(idempotency.requireEqual(material, OPERATION, replay.get()));
    Instant now = clock.instant();
    Optional<ReservationRecord> reservation = store.findReservation(contact);
    if (reservation.isEmpty() || !reservation.get().expiresAt().isAfter(now)) {
      claimDedup(requestId, material, "REJECTED_PROOF", now);
      return false;
    }
    Optional<LockedChallenge> locked = store.lockChallenge(reservation.get().challengeId());
    if (locked.isEmpty()
        || !"ACTIVE".equals(locked.get().state())
        || !locked.get().expiresAt().isAfter(now)) {
      claimDedup(requestId, material, "REJECTED_PROOF", now);
      return false;
    }
    LockedChallenge challenge = locked.get();
    if (!challenges.matches(
        challenge.challengeId(), code, challenge.verifier(), challenge.verifierKeyId())) {
      int failures = Math.min(5, challenge.failedAttempts() + 1);
      if (!claimDedupBeforeEffect(requestId, material, "REJECTED_PROOF", now)) {
        return "CONFIRMED"
            .equals(
                idempotency.requireEqual(
                    material, OPERATION, store.findDedup(requestId).orElseThrow()));
      }
      store.recordFailedProof(challenge.challengeId(), failures, failures >= 5, now);
      return false;
    }
    if (!claimDedupBeforeEffect(requestId, material, "CONFIRMED", now)) {
      return "CONFIRMED"
          .equals(
              idempotency.requireEqual(
                  material, OPERATION, store.findDedup(requestId).orElseThrow()));
    }
    store.confirm(challenge.userId(), challenge.contactId(), challenge.challengeId(), now);
    return true;
  }

  private boolean claimDedupBeforeEffect(
      UUID requestId, byte[] material, String outcome, Instant now) {
    FingerprintDigest fp = fingerprints.digest(material);
    return store.tryInsertDedup(
        requestId, OPERATION, fp.value(), fp.version(), fp.keyId(), outcome, now);
  }

  private void claimDedup(UUID requestId, byte[] material, String outcome, Instant now) {
    FingerprintDigest fp = fingerprints.digest(material);
    if (!store.tryInsertDedup(
        requestId, OPERATION, fp.value(), fp.version(), fp.keyId(), outcome, now)) {
      idempotency.requireEqual(material, OPERATION, store.findDedup(requestId).orElseThrow());
    }
  }
}
