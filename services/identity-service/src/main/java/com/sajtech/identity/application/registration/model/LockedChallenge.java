package com.sajtech.identity.application.registration.model;

import java.time.Instant;
import java.util.UUID;

public record LockedChallenge(
    UUID userId,
    UUID contactId,
    UUID challengeId,
    byte[] verifier,
    String verifierKeyId,
    int failedAttempts,
    Instant expiresAt,
    String state) {
  public LockedChallenge {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
