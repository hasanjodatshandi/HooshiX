package com.sajtech.identity.application.password.port.out;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

public interface PasswordRecoveryStore {
  void create(UUID challengeId, UUID userId, UUID contactId, byte[] verifier, String keyId, Instant expiresAt);
  Optional<RecoveryChallenge> find(UUID challengeId);
  void markUsed(UUID challengeId, Instant now);
  Optional<RecoveryTarget> findTargetByContact(String contact);

  record RecoveryTarget(UUID userId, UUID contactId, com.sajtech.identity.domain.registration.valueobject.CanonicalContact contact) {}
  record RecoveryChallenge(UUID id, UUID userId, byte[] verifier, String keyId, Instant expiresAt, String state) {}
}
