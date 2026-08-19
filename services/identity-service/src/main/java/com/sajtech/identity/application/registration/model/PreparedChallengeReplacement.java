package com.sajtech.identity.application.registration.model;

import java.time.Instant;
import java.util.UUID;

public record PreparedChallengeReplacement(
    UUID oldChallengeId,
    UUID newChallengeId,
    UUID outboxId,
    UUID notificationRequestId,
    byte[] verifier,
    String verifierKeyId,
    EncryptedHandoff handoff,
    Instant createdAt,
    Instant expiresAt) {
  public PreparedChallengeReplacement {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
