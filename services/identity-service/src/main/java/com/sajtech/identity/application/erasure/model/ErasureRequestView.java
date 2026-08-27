package com.sajtech.identity.application.erasure.model;

import java.time.Instant;
import java.util.UUID;

public record ErasureRequestView(
    UUID erasureRequestId,
    UUID userId,
    String state,
    String participantPolicyVersion,
    Instant acceptedAt,
    Instant completedAt) {
  public ErasureRequestView {
    if (erasureRequestId == null
        || userId == null
        || state == null
        || participantPolicyVersion == null
        || acceptedAt == null) {
      throw new IllegalArgumentException("Erasure request view is invalid");
    }
  }
}
