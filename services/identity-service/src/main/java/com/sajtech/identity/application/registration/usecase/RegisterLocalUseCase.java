package com.sajtech.identity.application.registration.usecase;

import com.sajtech.identity.application.password.model.PasswordPolicy;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.in.RegisterLocal;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

public final class RegisterLocalUseCase implements RegisterLocal {
  private static final String OPERATION = "REGISTER";
  private static final Duration CHALLENGE_TTL = Duration.ofMinutes(10);
  private final boolean phoneRegistrationEnabled;
  private final ContactCanonicalizer contacts;
  private final ProfileCanonicalizer profiles;
  private final PasswordNormalizer passwords;
  private final FingerprintMaterialEncoder fingerprintEncoder;
  private final IntentFingerprintPort fingerprints;
  private final IdempotencyGuard idempotency;
  private final SemanticQuotaPort quota;
  private final CompromisedPasswordPort compromisedPasswords;
  private final PasswordHashPort passwordHashes;
  private final ChallengeSecretPort challengeSecrets;
  private final NotificationEscrowPort escrow;
  private final TransactionRunner transactions;
  private final RegistrationStore store;
  private final Clock clock;

  public RegisterLocalUseCase(
      boolean phoneRegistrationEnabled,
      ContactCanonicalizer contacts,
      ProfileCanonicalizer profiles,
      PasswordNormalizer passwords,
      FingerprintMaterialEncoder fingerprintEncoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      CompromisedPasswordPort compromisedPasswords,
      PasswordHashPort passwordHashes,
      ChallengeSecretPort challengeSecrets,
      NotificationEscrowPort escrow,
      TransactionRunner transactions,
      RegistrationStore store,
      Clock clock) {
    this.phoneRegistrationEnabled = phoneRegistrationEnabled;
    this.contacts = contacts;
    this.profiles = profiles;
    this.passwords = passwords;
    this.fingerprintEncoder = fingerprintEncoder;
    this.fingerprints = fingerprints;
    this.idempotency = idempotency;
    this.quota = quota;
    this.compromisedPasswords = compromisedPasswords;
    this.passwordHashes = passwordHashes;
    this.challengeSecrets = challengeSecrets;
    this.escrow = escrow;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void register(RegisterLocalCommand command) {
    CanonicalContact contact = contacts.canonicalize(command.channel(), command.contact());
    if (contact.channel() == RegistrationChannel.PHONE && !phoneRegistrationEnabled) {
      throw new RegistrationException(
          RegistrationError.PHONE_REGISTRATION_DISABLED, "Phone registration is unavailable");
    }
    RegistrationProfile profile =
        profiles.canonicalize(command.firstName(), command.lastName(), command.fatherName());
    String password = passwords.normalize(command.password());
    try {
      PasswordPolicy.validate(password);
    } catch (IllegalArgumentException exception) {
      throw new RegistrationException(
          RegistrationError.INVALID_ARGUMENT, "Password input is invalid");
    }
    byte[] material = fingerprintEncoder.register(contact, password, command.locale(), profile);
    try {
      Optional<CommandDedupRecord> replay = store.findDedup(command.requestId());
      if (replay.isPresent()) {
        idempotency.requireEqual(material, OPERATION, replay.get());
        return;
      }

      quota.consume(new QuotaRequest(QuotaOperation.REGISTER, contact, command.clientAddress()));
      boolean needsCreation =
          transactions.required(() -> inspectForCreation(command.requestId(), material, contact));
      if (!needsCreation) {
        return;
      }

      compromisedPasswords.requireNotCompromised(password);
      String passwordHash = passwordHashes.hash(password);
      Instant now = clock.instant();
      UUID challengeId = UUID.randomUUID();
      GeneratedChallenge generated = challengeSecrets.generate(challengeId);
      UUID outboxId = UUID.randomUUID();
      EncryptedHandoff encrypted =
          escrow.encrypt(outboxId, contact, command.locale(), generated.code());
      PreparedRegistration prepared =
          new PreparedRegistration(
              UUID.randomUUID(),
              UUID.randomUUID(),
              challengeId,
              outboxId,
              UUID.randomUUID(),
              contact,
              profile,
              passwordHash,
              command.locale(),
              generated.verifier(),
              generated.keyId(),
              encrypted,
              now,
              now.plus(CHALLENGE_TTL));
      FingerprintDigest fingerprint = fingerprints.digest(material);
      transactions.required(
          () -> {
            commitPrepared(command.requestId(), material, fingerprint, prepared);
            return null;
          });
    } finally {
      Arrays.fill(material, (byte) 0);
    }
  }

  private boolean inspectForCreation(UUID requestId, byte[] material, CanonicalContact contact) {
    store.lockContactKey(contact);
    Optional<CommandDedupRecord> replay = store.findDedup(requestId);
    if (replay.isPresent()) {
      idempotency.requireEqual(material, OPERATION, replay.get());
      return false;
    }
    Instant now = clock.instant();
    if (store.verifiedContactExists(contact)) {
      FingerprintDigest fp = fingerprints.digest(material);
      claimDedup(requestId, material, fp, "ACCEPTED", now);
      return false;
    }
    Optional<ReservationRecord> reservation = store.findReservation(contact);
    if (reservation.isPresent() && reservation.get().expiresAt().isAfter(now)) {
      FingerprintDigest fp = fingerprints.digest(material);
      claimDedup(requestId, material, fp, "ACCEPTED", now);
      return false;
    }
    return true;
  }

  private void commitPrepared(
      UUID requestId,
      byte[] material,
      FingerprintDigest fingerprint,
      PreparedRegistration prepared) {
    store.lockContactKey(prepared.contact());
    Optional<CommandDedupRecord> replay = store.findDedup(requestId);
    if (replay.isPresent()) {
      idempotency.requireEqual(material, OPERATION, replay.get());
      return;
    }
    Instant now = clock.instant();
    if (store.verifiedContactExists(prepared.contact())) {
      claimDedup(requestId, material, fingerprint, "ACCEPTED", now);
      return;
    }
    Optional<ReservationRecord> current = store.findReservation(prepared.contact());
    if (current.isPresent() && current.get().expiresAt().isAfter(now)) {
      claimDedup(requestId, material, fingerprint, "ACCEPTED", now);
      return;
    }
    current.ifPresent(r -> store.expireChallenge(r.challengeId(), now));
    if (!store.tryInsertDedup(
        requestId,
        OPERATION,
        fingerprint.value(),
        fingerprint.version(),
        fingerprint.keyId(),
        "ACCEPTED",
        now)) {
      CommandDedupRecord stored =
          store
              .findDedup(requestId)
              .orElseThrow(
                  () ->
                      new RegistrationException(
                          RegistrationError.DEPENDENCY_UNAVAILABLE,
                          "Idempotency state is unavailable"));
      idempotency.requireEqual(material, OPERATION, stored);
      return;
    }
    store.insertRegistration(prepared);
  }

  private void claimDedup(
      UUID requestId, byte[] material, FingerprintDigest fp, String outcome, Instant now) {
    if (!store.tryInsertDedup(
        requestId, OPERATION, fp.value(), fp.version(), fp.keyId(), outcome, now)) {
      CommandDedupRecord stored =
          store
              .findDedup(requestId)
              .orElseThrow(
                  () ->
                      new RegistrationException(
                          RegistrationError.DEPENDENCY_UNAVAILABLE,
                          "Idempotency state is unavailable"));
      idempotency.requireEqual(material, OPERATION, stored);
    }
  }
}
