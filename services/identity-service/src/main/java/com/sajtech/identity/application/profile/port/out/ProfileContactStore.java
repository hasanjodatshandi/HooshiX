package com.sajtech.identity.application.profile.port.out;

import com.sajtech.identity.application.profile.model.*;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileContactStore {
  ProfileRecord findProfile(UUID userId);

  void updateProfile(
      UUID userId, String firstName, String lastName, String fatherName, Instant updatedAt);

  List<ContactRecord> findContacts(UUID userId, int limit);

  void lockUser(UUID userId);

  void lockContactKey(CanonicalContact contact);

  boolean contactKeyUnavailable(CanonicalContact contact, UUID allowedContactId, Instant now);

  int countActiveContacts(UUID userId);

  void insertContactChallenge(UUID userId, PreparedContactChallenge prepared);

  Optional<LockedContactChallenge> lockLatestChallenge(UUID userId, UUID contactId);

  void replaceChallenge(LockedContactChallenge previous, PreparedContactChallenge prepared);

  void recordFailedProof(UUID challengeId, int failures, boolean exhausted, Instant now);

  void confirmContact(LockedContactChallenge challenge, Instant now);

  boolean setPrimary(UUID userId, UUID contactId, Instant now);

  boolean remove(UUID userId, UUID contactId, Instant now);

  Optional<ProfileCommandRecord> findCommand(UUID requestId);

  boolean tryInsertCommand(
      UUID requestId,
      UUID userId,
      String operation,
      FingerprintDigest fingerprint,
      String outcome,
      UUID resultId,
      Instant now);

  int deleteCommandsBefore(Instant cutoff, int batch);

  record ProfileRecord(UUID userId, String firstName, String lastName, String fatherName) {}

  record ContactRecord(UUID id, String type, String value, boolean verified, boolean primary) {}
}
