package com.sajtech.identity.application.mfa.port.out;

import com.sajtech.identity.application.mfa.model.GeneratedMfaChallenge;
import java.time.Instant;
import java.util.UUID;

public interface MfaAuthenticationGate {
  boolean requiresMfa(UUID userId);

  void replaceLoginChallenge(
      UUID challengeId,
      UUID userId,
      GeneratedMfaChallenge challenge,
      Instant now,
      Instant expiresAt);
}
