package com.sajtech.identity.application.registration;

import com.sajtech.identity.application.registration.model.ConfirmRegistrationCommand;
import com.sajtech.identity.application.registration.model.ConfirmWrite;
import com.sajtech.identity.application.registration.model.IdempotencyRecord;
import com.sajtech.identity.application.registration.model.PendingRegistrationSnapshot;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.port.in.ConfirmRegistration;
import com.sajtech.identity.application.registration.port.out.RegistrationCryptoPort;
import com.sajtech.identity.application.registration.port.out.RegistrationPersistencePort;
import com.sajtech.identity.application.registration.port.out.RegistrationQuotaPort;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

public final class ConfirmRegistrationService implements ConfirmRegistration {
  private final RegistrationPersistencePort persistence;
  private final RegistrationQuotaPort quota;
  private final RegistrationCryptoPort crypto;
  private final Clock clock;

  public ConfirmRegistrationService(
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
  public void confirm(ConfirmRegistrationCommand command) {
    if (command.code() == null || !command.code().matches("[0-9]{8}")) {
      throw new RegistrationException(RegistrationError.INVALID_REGISTRATION_CHALLENGE);
    }
    byte[] canonical =
        CanonicalIntent.encode(
            command.contact().kind().name(), command.contact().canonicalValue(), command.code());
    RequestFingerprint fingerprint;
    try {
      Optional<IdempotencyRecord> previous =
          persistence.findIdempotency(command.requestId(), RequestPurpose.CONFIRM_REGISTRATION);
      if (previous.isPresent()) {
        requireReplay(RequestPurpose.CONFIRM_REGISTRATION, canonical, previous.get());
        return;
      }
      fingerprint = crypto.fingerprint(RequestPurpose.CONFIRM_REGISTRATION, canonical);
    } finally {
      Arrays.fill(canonical, (byte) 0);
    }

    quota.acquire(RequestPurpose.CONFIRM_REGISTRATION, command.contact(), command.trustedClientIp());
    Instant now = now();
    PendingRegistrationSnapshot pending =
        persistence
            .findPending(command.contact(), now)
            .orElseThrow(
                () -> new RegistrationException(RegistrationError.INVALID_REGISTRATION_CHALLENGE));
    if (pending.failedAttempts() >= 5 || !crypto.matchesChallenge(command.code(), pending.verifier())) {
      persistence.recordFailedAttempt(pending, now);
      throw new RegistrationException(RegistrationError.INVALID_REGISTRATION_CHALLENGE);
    }

    boolean confirmed =
        persistence.confirm(new ConfirmWrite(command.requestId(), fingerprint, pending, now));
    if (!confirmed) {
      throw new RegistrationException(RegistrationError.INVALID_REGISTRATION_CHALLENGE);
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
