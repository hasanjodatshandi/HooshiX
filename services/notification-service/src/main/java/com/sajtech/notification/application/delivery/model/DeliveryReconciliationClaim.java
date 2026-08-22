package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Instant;
import java.util.UUID;

public record DeliveryReconciliationClaim(
    UUID notificationId,
    UUID attemptId,
    UUID executionId,
    NotificationChannel channel,
    NotificationLifecycle lifecycle,
    Instant observationStartedAt,
    String providerCorrelationId) {
  public DeliveryReconciliationClaim {
    if (notificationId == null
        || attemptId == null
        || executionId == null
        || channel == null
        || (lifecycle != NotificationLifecycle.DISPATCHING
            && lifecycle != NotificationLifecycle.PROVIDER_ACCEPTED)
        || observationStartedAt == null) {
      throw new IllegalArgumentException("Delivery reconciliation claim is invalid");
    }
  }
}
