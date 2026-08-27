package com.sajtech.identity.application.erasure.model;

import java.time.Instant;
import java.util.UUID;

public record ErasureCommandOutboxItem(
    UUID eventId,
    UUID erasureRequestId,
    String participantPolicyVersion,
    int attemptCount,
    Instant occurredAt) {
  public ErasureCommandOutboxItem {
    if (eventId == null
        || erasureRequestId == null
        || participantPolicyVersion == null
        || attemptCount < 0
        || occurredAt == null) {
      throw new IllegalArgumentException("Erasure command outbox item is invalid");
    }
  }
}
