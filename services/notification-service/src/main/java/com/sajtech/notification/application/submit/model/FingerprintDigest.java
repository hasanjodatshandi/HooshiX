package com.sajtech.notification.application.submit.model;

import java.util.Arrays;

public record FingerprintDigest(String version, String keyId, byte[] value) {
  public FingerprintDigest {
    if (version == null || version.isBlank() || keyId == null || keyId.isBlank()) {
      throw new IllegalArgumentException("Fingerprint version and key identifier are required");
    }
    if (value == null || value.length != 32) {
      throw new IllegalArgumentException("Fingerprint digest must contain exactly 32 bytes");
    }
    value = Arrays.copyOf(value, value.length);
  }

  @Override
  public byte[] value() {
    return Arrays.copyOf(value, value.length);
  }
}
