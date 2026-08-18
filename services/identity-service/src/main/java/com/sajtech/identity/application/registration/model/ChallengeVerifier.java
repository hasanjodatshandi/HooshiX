package com.sajtech.identity.application.registration.model;

import java.util.Arrays;
import java.util.Objects;

public record ChallengeVerifier(String keyId, byte[] digest) {
  public ChallengeVerifier {
    Objects.requireNonNull(keyId, "keyId");
    Objects.requireNonNull(digest, "digest");
    if (digest.length != 32) {
      throw new IllegalArgumentException("challenge digest must be 32 bytes");
    }
    digest = Arrays.copyOf(digest, digest.length);
  }

  @Override
  public byte[] digest() {
    return Arrays.copyOf(digest, digest.length);
  }
}
