package com.sajtech.identity.application.externalidentity.model;

public record EncryptedExternalIdentityResult(String keyId, byte[] nonce, byte[] ciphertext) {
  public EncryptedExternalIdentityResult {
    nonce = nonce.clone();
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
