package com.sajtech.identity.application.registration.model;

public record EncryptedHandoff(String keyId, byte[] nonce, byte[] ciphertext) {
  public EncryptedHandoff {
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
