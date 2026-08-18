package com.sajtech.identity.application.notification.port.out;

import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationOutboxStore {
  List<NotificationOutboxRecord> claimDue(Instant now, int batch, Duration lease);

  void markSubmitted(UUID outboxId, Instant now);

  void reschedule(
      UUID outboxId, int attemptCount, Instant nextAttempt, Instant now, String safeErrorClass);

  void markPermanentFailure(UUID outboxId, Instant now, String safeErrorClass);

  int eraseExpiredSensitive(Instant now, int batch);
}
