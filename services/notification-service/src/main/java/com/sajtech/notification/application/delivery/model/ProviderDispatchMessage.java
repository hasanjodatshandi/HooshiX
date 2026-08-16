package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.util.UUID;

public record ProviderDispatchMessage(
    UUID notificationId,
    UUID executionId,
    NotificationChannel channel,
    String recipient,
    String subject,
    String text,
    String html) {
  public ProviderDispatchMessage {
    if (notificationId == null
        || executionId == null
        || channel == null
        || recipient == null
        || recipient.isBlank()
        || text == null
        || text.isBlank()) {
      throw new IllegalArgumentException("Provider dispatch message is incomplete");
    }
  }
}
