package com.sajtech.identity.application.mfa.port.out;

import com.sajtech.identity.application.mfa.model.*;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public interface MfaCryptographyPort {
  GeneratedTotpSecret generateTotpSecret(UUID userId, UUID enrollmentId);

  OptionalLong verifyTotp(
      UUID userId, UUID enrollmentId, EncryptedTotpSecret encrypted, String code, Instant now);

  GeneratedMfaChallenge generateChallenge();

  List<MfaDigest> challengeDigestCandidates(String encoded);

  List<GeneratedRecoveryCode> generateRecoveryCodes(UUID enrollmentId);

  List<MfaDigest> recoveryDigestCandidates(UUID enrollmentId, String encoded);
}
