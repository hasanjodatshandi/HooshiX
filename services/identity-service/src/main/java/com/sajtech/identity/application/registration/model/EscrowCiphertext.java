package com.sajtech.identity.application.registration.model;

import java.util.Arrays;
import java.util.Objects;

public record EscrowCiphertext(String keyId, byte[] nonce, byte[] ciphertext) {
  public EscrowCiphertext {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(nonce, "nonce");
    Objects.requireNonNull(ciphertext, "ciphertext");
    if (nonce.length != 12) {
      throw new IllegalArgumentException("AES-GCM nonce must be 12 bytes");
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
