package com.sajtech.identity.application.mfa.model;

public record EncryptedTotpSecret(String keyId, byte[] nonce, byte[] ciphertext) {
  public EncryptedTotpSecret {
    if (keyId == null
        || keyId.isBlank()
        || nonce == null
        || nonce.length != 12
        || ciphertext == null
        || ciphertext.length != 48) {
      throw new IllegalArgumentException("Encrypted TOTP secret is invalid");
    }
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
