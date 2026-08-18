package com.sajtech.identity.application.registration.model;

import java.util.Arrays;
import java.util.Objects;

public record RequestFingerprint(short version, String keyId, byte[] digest) {
  public RequestFingerprint {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(digest, "digest");
    if (digest.length != 32) {
      throw new IllegalArgumentException("fingerprint digest must be 32 bytes");
    }
    digest = Arrays.copyOf(digest, digest.length);
  }

  @Override
  public byte[] digest() {
    return Arrays.copyOf(digest, digest.length);
  }
}
