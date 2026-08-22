package com.sajtech.identity.application.notification.model;

import java.time.Instant;
import java.util.UUID;

public record NotificationTerminalResult(
    UUID notificationId, NotificationTerminalLifecycle lifecycle, Instant occurredAt) {
  public NotificationTerminalResult {
    if (notificationId == null || lifecycle == null || occurredAt == null) {
      throw new IllegalArgumentException("Notification terminal result is incomplete");
    }
  }
}
