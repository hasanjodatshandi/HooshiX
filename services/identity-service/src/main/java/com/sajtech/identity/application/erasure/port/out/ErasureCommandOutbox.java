package com.sajtech.identity.application.erasure.port.out;

import com.sajtech.identity.application.erasure.model.ErasureCommandOutboxItem;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ErasureCommandOutbox {
  List<ErasureCommandOutboxItem> claimDue(int batchSize, Instant now, Duration lease);

  void markPublished(UUID eventId, Instant now);

  void reschedule(
      UUID eventId, int attemptCount, Instant nextAttempt, Instant now, String safeErrorClass);

  void markExhausted(UUID eventId, int attemptCount, Instant now, String safeErrorClass);
}
