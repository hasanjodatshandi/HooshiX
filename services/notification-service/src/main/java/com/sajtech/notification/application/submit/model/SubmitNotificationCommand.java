package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Instant;
import java.util.UUID;

public record SubmitNotificationCommand(
    UUID requestId,
    NotificationChannel channel,
    String recipient,
    String locale,
    Instant messageNotAfter,
    SemanticContent semanticContent) {
  public SubmitNotificationCommand {
    if (requestId == null || channel == null || recipient == null || semanticContent == null) {
      throw new IllegalArgumentException("Notification command fields are required");
    }
  }
}
