package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.CommandDedupRecord;
import com.sajtech.identity.application.registration.model.LockedChallenge;
import com.sajtech.identity.application.registration.model.PreparedChallengeReplacement;
import com.sajtech.identity.application.registration.model.PreparedRegistration;
import com.sajtech.identity.application.registration.model.ReservationRecord;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RegistrationStore {
  Optional<CommandDedupRecord> findDedup(UUID requestId);

  void lockContactKey(CanonicalContact contact);

  boolean verifiedContactExists(CanonicalContact contact);

  Optional<ReservationRecord> findReservation(CanonicalContact contact);

  Optional<LockedChallenge> lockChallenge(UUID challengeId);

  void expireChallenge(UUID challengeId, Instant now);

  void insertRegistration(PreparedRegistration registration);

  void replaceChallenge(
      CanonicalContact contact,
      ReservationRecord reservation,
      PreparedChallengeReplacement replacement);

  void recordFailedProof(UUID challengeId, int failedAttempts, boolean exhausted, Instant now);

  void confirm(UUID userId, UUID contactId, UUID challengeId, Instant now);

  boolean tryInsertDedup(
      UUID requestId,
      String operation,
      byte[] fingerprint,
      String fingerprintVersion,
      String fingerprintKeyId,
      String outcome,
      Instant now);

  int deleteDedupBefore(Instant cutoff, int batch);
}
