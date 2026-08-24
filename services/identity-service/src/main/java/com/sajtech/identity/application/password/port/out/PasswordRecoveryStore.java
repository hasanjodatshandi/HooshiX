package com.sajtech.identity.application.password.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordRecoveryStore {
  boolean requestAlreadyAccepted(UUID requestId);

  void create(PreparedPasswordRecovery recovery);

  Optional<RecoveryChallenge> findActiveByContact(String canonicalContact, Instant now);

  Optional<RecoveryChallenge> lockActiveByContact(String canonicalContact, Instant now);

  void recordFailedProof(UUID challengeId, Instant now);

  void markUsed(UUID challengeId, UUID requestId, Instant now);

  boolean confirmationAlreadyCompleted(UUID requestId);

  Optional<RecoveryTarget> findTargetByContact(
      com.sajtech.identity.domain.registration.valueobject.CanonicalContact contact);

  default Optional<RecoveryTarget> lockTargetByContact(
      com.sajtech.identity.domain.registration.valueobject.CanonicalContact contact) {
    return findTargetByContact(contact);
  }

  record RecoveryTarget(
      UUID userId,
      UUID contactId,
      com.sajtech.identity.domain.registration.valueobject.CanonicalContact contact) {}

  record RecoveryChallenge(
      UUID id,
      UUID userId,
      byte[] verifier,
      String keyId,
      Instant expiresAt,
      String state,
      int failedAttempts) {
    public RecoveryChallenge {
      verifier = verifier.clone();
    }

    @Override
    public byte[] verifier() {
      return verifier.clone();
    }
  }
}
