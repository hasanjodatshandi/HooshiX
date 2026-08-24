package com.sajtech.identity.application.password.port.out;

import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import java.time.Instant;
import java.util.UUID;

public interface PasswordLifecycleStore {
  void updatePasswordHash(UUID userId, String passwordHash, Instant now);
  void revokeSessions(UUID userId, RefreshFamilyRevocationReason reason, Instant now);
}
