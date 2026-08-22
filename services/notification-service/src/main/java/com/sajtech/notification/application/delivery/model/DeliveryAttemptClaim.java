package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Instant;
import java.util.UUID;

public record DeliveryAttemptClaim(
    UUID notificationId,
    UUID attemptId,
    UUID executionId,
    int attemptNumber,
    NotificationChannel channel,
    Instant effectiveDeliveryDeadline,
    DeliveryEscrowEnvelope escrow) {
  public DeliveryAttemptClaim {
    if (notificationId == null
        || attemptId == null
        || executionId == null
        || attemptNumber < 1
        || attemptNumber > 4
        || channel == null
        || effectiveDeliveryDeadline == null
        || escrow == null
        || !notificationId.equals(escrow.notificationId())
        || channel != escrow.channel()) {
      throw new IllegalArgumentException("Delivery attempt claim is invalid");
    }
  }
}
