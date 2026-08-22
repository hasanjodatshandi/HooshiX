package com.sajtech.notification.application.result.model;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Instant;
import java.util.UUID;

public record NotificationResultOutboxRecord(
    UUID outboxId,
    UUID notificationId,
    NotificationLifecycle terminalLifecycle,
    Instant occurredAt,
    int attemptCount) {
  public NotificationResultOutboxRecord {
    if (outboxId == null
        || notificationId == null
        || terminalLifecycle == null
        || !terminalLifecycle.isTerminal()
        || occurredAt == null
        || attemptCount < 0) {
      throw new IllegalArgumentException("Notification result outbox record is invalid");
    }
  }
}
