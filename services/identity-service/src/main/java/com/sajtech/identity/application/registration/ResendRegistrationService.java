package com.sajtech.identity.application.registration;

import com.sajtech.identity.application.registration.model.ChallengeVerifier;
import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import com.sajtech.identity.application.registration.model.IdempotencyRecord;
import com.sajtech.identity.application.registration.model.PendingRegistrationSnapshot;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.model.ResendRegistrationCommand;
import com.sajtech.identity.application.registration.model.ResendWrite;
import com.sajtech.identity.application.registration.port.in.ResendRegistrationVerification;
import com.sajtech.identity.application.registration.port.out.RegistrationCryptoPort;
import com.sajtech.identity.application.registration.port.out.RegistrationPersistencePort;
import com.sajtech.identity.application.registration.port.out.RegistrationQuotaPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ResendRegistrationService implements ResendRegistrationVerification {
  private final RegistrationPersistencePort persistence;
  private final RegistrationQuotaPort quota;
  private final RegistrationCryptoPort crypto;
  private final Clock clock;

  public ResendRegistrationService(
      RegistrationPersistencePort persistence,
      RegistrationQuotaPort quota,
      RegistrationCryptoPort crypto,
      Clock clock) {
    this.persistence = Objects.requireNonNull(persistence, "persistence");
    this.quota = Objects.requireNonNull(quota, "quota");
    this.crypto = Objects.requireNonNull(crypto, "crypto");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void resend(ResendRegistrationCommand command) {
    byte[] canonical =
        CanonicalIntent.encode(command.contact().kind().name(), command.contact().canonicalValue());
    RequestFingerprint fingerprint;
    try {
      Optional<IdempotencyRecord> previous =
          persistence.findIdempotency(
              command.requestId(), RequestPurpose.RESEND_REGISTRATION_VERIFICATION);
      if (previous.isPresent()) {
        requireReplay(RequestPurpose.RESEND_REGISTRATION_VERIFICATION, canonical, previous.get());
        return;
      }
      fingerprint =
          crypto.fingerprint(RequestPurpose.RESEND_REGISTRATION_VERIFICATION, canonical);
    } finally {
      Arrays.fill(canonical, (byte) 0);
    }

    quota.acquire(
        RequestPurpose.RESEND_REGISTRATION_VERIFICATION,
        command.contact(),
        command.trustedClientIp());
    Instant now = now();
    Optional<PendingRegistrationSnapshot> pending = persistence.findPending(command.contact(), now);
    if (pending.isEmpty() || now.isBefore(pending.get().resendNotBefore())) {
      persistence.recordNeutralAcceptance(
          command.requestId(), RequestPurpose.RESEND_REGISTRATION_VERIFICATION, fingerprint, now);
      return;
    }

    PendingRegistrationSnapshot snapshot = pending.get();
    String code = crypto.newVerificationCode();
    ChallengeVerifier verifier = crypto.challengeVerifier(code);
    Instant expiresAt = now.plus(10, ChronoUnit.MINUTES);
    UUID outboxId = UUID.randomUUID();
    byte[] plaintext =
        new OutboxPayload(
                snapshot.contactKind(),
                snapshot.deliveryValue(),
                snapshot.locale(),
                code,
                expiresAt)
            .encode();
    EscrowCiphertext escrow;
    try {
      escrow = crypto.encryptCallerEscrow(outboxId, plaintext);
    } finally {
      Arrays.fill(plaintext, (byte) 0);
    }
    boolean replaced =
        persistence.replaceChallenge(
            new ResendWrite(
                command.requestId(),
                fingerprint,
                snapshot,
                UUID.randomUUID(),
                outboxId,
                UUID.randomUUID(),
                verifier,
                escrow,
                now,
                expiresAt,
                now.plus(60, ChronoUnit.SECONDS)));
    if (!replaced) {
      persistence.recordNeutralAcceptance(
          command.requestId(), RequestPurpose.RESEND_REGISTRATION_VERIFICATION, fingerprint, now);
    }
  }

  private void requireReplay(
      RequestPurpose purpose, byte[] canonical, IdempotencyRecord previous) {
    if (!crypto.verifyFingerprint(purpose, canonical, previous.fingerprint())) {
      throw new RegistrationException(RegistrationError.REQUEST_ID_CONFLICT);
    }
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }
}
