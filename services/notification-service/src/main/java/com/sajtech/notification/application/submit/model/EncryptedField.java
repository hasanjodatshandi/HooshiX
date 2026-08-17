package com.sajtech.notification.application.submit.model;

import java.util.Arrays;

public record EncryptedField(byte[] nonce, byte[] ciphertext) {
  public EncryptedField {
    if (nonce == null || nonce.length != 12) {
      throw new IllegalArgumentException("AES-GCM nonce must contain exactly 12 bytes");
    }
    if (ciphertext == null || ciphertext.length < 16) {
      throw new IllegalArgumentException("AES-GCM ciphertext must include an authentication tag");
    }
    nonce = Arrays.copyOf(nonce, nonce.length);
    ciphertext = Arrays.copyOf(ciphertext, ciphertext.length);
  }

  @Override
  public byte[] nonce() {
    return Arrays.copyOf(nonce, nonce.length);
  }

  @Override
  public byte[] ciphertext() {
    return Arrays.copyOf(ciphertext, ciphertext.length);
  }
}
