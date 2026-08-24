package com.sajtech.notification.application.result.port.out;

import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface NotificationResultOutboxRepository {
  List<NotificationResultOutboxRecord> claimDue(int batchSize, Duration lease);

  void markCompleted(UUID outboxId);

  void reschedule(UUID outboxId, int attemptCount, Duration delay, String safeErrorClass);

  void markExhausted(UUID outboxId, int attemptCount, String safeErrorClass);
}
