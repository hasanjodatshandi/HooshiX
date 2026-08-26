package com.sajtech.identity.application.externalidentity.port.out;

import com.sajtech.identity.application.externalidentity.model.EncryptedExternalIdentityResult;

public interface ExternalIdentityResultCryptoPort {
  EncryptedExternalIdentityResult encrypt(byte[] evidenceId, String operation, byte[] clear);

  byte[] decrypt(byte[] evidenceId, String operation, EncryptedExternalIdentityResult encrypted);
}
