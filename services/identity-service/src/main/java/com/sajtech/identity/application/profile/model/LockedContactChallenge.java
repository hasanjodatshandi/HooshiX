package com.sajtech.identity.application.profile.model;

import java.time.Instant;
import java.util.UUID;

public record LockedContactChallenge(
    UUID challengeId,
    UUID contactId,
    UUID userId,
    byte[] verifier,
    String verifierKeyId,
    String state,
    int failedAttempts,
    Instant expiresAt,
    Instant lastSentAt,
    String channel,
    String deliveryValue,
    String locale) {
  public LockedContactChallenge {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
