package com.sajtech.conversation.infrastructure.security.content;

import java.util.Arrays;

public record EncryptedContent(String keyId, byte[] nonce, byte[] ciphertext) {
  public EncryptedContent {
    if (keyId == null
        || !keyId.matches("[A-Za-z0-9._-]{1,64}")
        || nonce == null
        || nonce.length != 12
        || ciphertext == null
        || ciphertext.length < 16) {
      throw new IllegalArgumentException("Encrypted Conversation content is invalid");
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
