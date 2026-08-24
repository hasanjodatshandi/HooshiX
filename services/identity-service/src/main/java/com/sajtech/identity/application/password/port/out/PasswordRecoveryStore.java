package com.sajtech.identity.application.password.port.out;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;

public interface PasswordRecoveryStore {
  void create(UUID challengeId, UUID userId, UUID contactId, byte[] verifier, String keyId, Instant expiresAt);
  Optional<RecoveryTarget> findTargetByContact(String contact);

  record RecoveryTarget(UUID userId, UUID contactId) {}
}
