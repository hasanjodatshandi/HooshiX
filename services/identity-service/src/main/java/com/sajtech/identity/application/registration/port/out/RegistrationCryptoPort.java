package com.sajtech.identity.application.registration.port.out;

import com.sajtech.identity.application.registration.model.ChallengeVerifier;
import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import com.sajtech.identity.application.registration.model.RequestFingerprint;
import com.sajtech.identity.application.registration.model.RequestPurpose;
import java.util.UUID;

public interface RegistrationCryptoPort {
  RequestFingerprint fingerprint(RequestPurpose purpose, byte[] canonicalIntent);

  boolean verifyFingerprint(
      RequestPurpose purpose, byte[] canonicalIntent, RequestFingerprint storedFingerprint);

  String newVerificationCode();

  ChallengeVerifier challengeVerifier(String code);

  boolean matchesChallenge(String code, ChallengeVerifier verifier);

  EscrowCiphertext encryptCallerEscrow(UUID outboxId, byte[] plaintext);

  byte[] decryptCallerEscrow(UUID outboxId, EscrowCiphertext ciphertext);
}
