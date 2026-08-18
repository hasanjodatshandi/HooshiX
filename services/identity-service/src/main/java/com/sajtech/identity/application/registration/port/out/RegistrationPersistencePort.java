package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.ConfirmWrite;
import com.sajtech.identity.application.registration.model.IdempotencyRecord;
import com.sajtech.identity.application.registration.model.PendingRegistrationSnapshot;
import com.sajtech.identity.application.registration.model.RegistrationWrite;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import com.sajtech.identity.application.registration.model.ResendWrite;
import com.sajtech.identity.domain.registration.CanonicalContact;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationPersistencePort {
  Optional<IdempotencyRecord> findIdempotency(UUID requestId, RequestPurpose purpose);

  void createOrContinue(RegistrationWrite write);

  Optional<PendingRegistrationSnapshot> findPending(CanonicalContact contact, Instant now);

  void recordNeutralAcceptance(
      UUID requestId, RequestPurpose purpose, RequestFingerprint fingerprint, Instant now);

  boolean replaceChallenge(ResendWrite write);

  boolean recordFailedAttempt(PendingRegistrationSnapshot expected, Instant now);

  boolean confirm(ConfirmWrite write);
}
