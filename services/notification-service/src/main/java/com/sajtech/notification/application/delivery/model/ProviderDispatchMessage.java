package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Instant;
import java.util.UUID;

public record ProviderDispatchMessage(
    UUID notificationId,
    UUID attemptId,
    UUID executionId,
    int attemptNumber,
    NotificationChannel channel,
    Instant effectiveDeliveryDeadline,
    String recipient,
    String subject,
    String text,
    String html) {
  public ProviderDispatchMessage {
    if (notificationId == null
        || attemptId == null
        || executionId == null
        || attemptNumber < 1
        || attemptNumber > 4
        || channel == null
        || effectiveDeliveryDeadline == null
        || recipient == null
        || recipient.isBlank()
        || text == null
        || text.isBlank()) {
      throw new IllegalArgumentException("Provider dispatch message is incomplete");
    }
  }
}
