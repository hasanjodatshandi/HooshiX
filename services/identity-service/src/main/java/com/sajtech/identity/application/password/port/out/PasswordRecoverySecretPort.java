package com.sajtech.identity.application.password.port.out;

import com.sajtech.identity.application.password.model.GeneratedRecoveryProof;
import java.util.UUID;

public interface PasswordRecoverySecretPort {
  GeneratedRecoveryProof generate(UUID challengeId);

  boolean matches(UUID challengeId, String code, byte[] storedVerifier, String keyId);
}
