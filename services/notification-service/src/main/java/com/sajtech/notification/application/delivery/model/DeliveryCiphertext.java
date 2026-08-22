package com.sajtech.notification.application.delivery.model;

import java.util.Arrays;

public record DeliveryCiphertext(byte[] nonce, byte[] ciphertext) {
  public DeliveryCiphertext {
    if (nonce == null || nonce.length != 12 || ciphertext == null || ciphertext.length < 16) {
      throw new IllegalArgumentException("Delivery ciphertext is invalid");
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
