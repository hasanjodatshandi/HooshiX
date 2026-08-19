package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.GeneratedChallenge;
import java.util.UUID;

public interface ChallengeSecretPort {
  GeneratedChallenge generate(UUID challengeId);

  boolean matches(UUID challengeId, String code, byte[] storedVerifier, String keyId);
}
