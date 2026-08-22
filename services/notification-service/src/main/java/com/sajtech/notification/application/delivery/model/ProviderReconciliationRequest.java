package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.util.UUID;

public record ProviderReconciliationRequest(
    UUID notificationId,
    UUID attemptId,
    UUID executionId,
    NotificationChannel channel,
    String providerCorrelationId) {
  public ProviderReconciliationRequest {
    if (notificationId == null || attemptId == null || executionId == null || channel == null) {
      throw new IllegalArgumentException("Provider reconciliation request is incomplete");
    }
  }
}
