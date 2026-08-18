package com.sajtech.identity.application.registration;

import com.sajtech.identity.application.registration.model.ChallengeVerifier;
import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import com.sajtech.identity.application.registration.model.IdempotencyRecord;
import com.sajtech.identity.application.registration.model.RegisterLocalCommand;
import com.sajtech.identity.application.registration.model.RegistrationWrite;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.port.in.RegisterLocal;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import com.sajtech.identity.application.registration.port.out.PasswordScreeningPort;
import com.sajtech.identity.application.registration.port.out.RegistrationCryptoPort;
import com.sajtech.identity.application.registration.port.out.RegistrationPersistencePort;
import com.sajtech.identity.application.registration.port.out.RegistrationQuotaPort;
import com.sajtech.identity.domain.registration.ContactKind;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RegisterLocalService implements RegisterLocal {
  private static final int MAX_PASSWORD_UTF8_BYTES = 1024;

  private final RegistrationPersistencePort persistence;
  private final RegistrationQuotaPort quota;
  private final PasswordScreeningPort passwordScreening;
  private final PasswordHashPort passwordHash;
  private final RegistrationCryptoPort crypto;
  private final Clock clock;
  private final boolean phoneRegistrationEnabled;

  public RegisterLocalService(
      RegistrationPersistencePort persistence,
      RegistrationQuotaPort quota,
      PasswordScreeningPort passwordScreening,
      PasswordHashPort passwordHash,
      RegistrationCryptoPort crypto,
      Clock clock,
      boolean phoneRegistrationEnabled) {
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.quota = Objects.requireNonNull(quota, "quota");
    this.passwordScreening = Objects.requireNonNull(passwordScreening, "passwordScreening");
    this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
    this.crypto = Objects.requireNonNull(crypto, "crypto");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.phoneRegistrationEnabled = phoneRegistrationEnabled;
  }

  @Override
  public void register(RegisterLocalCommand command) {
    Objects.requireNonNull(command, "command");
    String password = normalizePassword(command.password());
    byte[] canonical =
        CanonicalIntent.encode(
            command.contact().kind().name(),
            command.contact().canonicalValue(),
            command.profile().firstName(),
            command.profile().lastName(),
            command.profile().fatherName(),
            password,
            command.locale().wireValue());
    RequestFingerprint fingerprint;
    try {
      Optional<IdempotencyRecord> previous =
          persistence.findIdempotency(command.requestId(), RequestPurpose.REGISTER);
      if (previous.isPresent()) {
        requireReplay(RequestPurpose.REGISTER, canonical, previous.get());
        return;
      }
      fingerprint = crypto.fingerprint(RequestPurpose.REGISTER, canonical);
    } finally {
      Arrays.fill(canonical, (byte) 0);
    }

    quota.acquire(RequestPurpose.REGISTER, command.contact(), command.trustedClientIp());
    if (command.contact().kind() == ContactKind.PHONE && !phoneRegistrationEnabled) {
      throw new RegistrationException(RegistrationError.PHONE_REGISTRATION_DISABLED);
    }

    passwordScreening.requireNotCompromised(password);
    String encodedPassword = passwordHash.hash(password);

    Instant now = now();
    Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);
    String code = crypto.newVerificationCode();
    ChallengeVerifier verifier = crypto.challengeVerifier(code);
    UUID outboxId = UUID.randomUUID();
    byte[] plaintext =
        new OutboxPayload(
                command.contact().kind(),
                command.contact().deliveryValue(),
                command.locale(),
                code,
                expiresAt)
            .encode();
    EscrowCiphertext escrow;
    try {
      escrow = crypto.encryptCallerEscrow(outboxId, plaintext);
    } finally {
      Arrays.fill(plaintext, (byte) 0);
    }

    persistence.createOrContinue(
        new RegistrationWrite(
            command.requestId(),
            fingerprint,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            outboxId,
            UUID.randomUUID(),
            command.contact(),
            command.profile(),
            encodedPassword,
            command.locale(),
            verifier,
            escrow,
            now,
            expiresAt,
            now.plus(60, ChronoUnit.SECONDS)));
  }

  private void requireReplay(
      RequestPurpose purpose, byte[] canonical, IdempotencyRecord previous) {
    if (!crypto.verifyFingerprint(purpose, canonical, previous.fingerprint())) {
      throw new RegistrationException(RegistrationError.REQUEST_ID_CONFLICT);
    }
  }

  private String normalizePassword(String raw) {
    if (raw == null || raw.isEmpty()) {
      throw new RegistrationException(RegistrationError.INVALID_REGISTRATION_REQUEST);
    }
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC);
    int length = normalized.getBytes(StandardCharsets.UTF_8).length;
    if (length < 1 || length > MAX_PASSWORD_UTF8_BYTES) {
      throw new RegistrationException(RegistrationError.INVALID_REGISTRATION_REQUEST);
    }
    return normalized;
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
