package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.util.UUID;

public record DeliveryEscrowEnvelope(
    UUID notificationId,
    String callerService,
    UUID requestId,
    NotificationChannel channel,
    NotificationSemanticType semanticType,
    UUID templateVersionId,
    int formatVersion,
    String keyId,
    DeliveryCiphertext recipient,
    DeliveryCiphertext subject,
    DeliveryCiphertext text,
    DeliveryCiphertext html) {
  public DeliveryEscrowEnvelope {
    if (notificationId == null
        || callerService == null
        || callerService.isBlank()
        || requestId == null
        || channel == null
        || semanticType == null
        || templateVersionId == null
        || formatVersion != 1
        || keyId == null
        || keyId.isBlank()
        || recipient == null
        || text == null) {
      throw new IllegalArgumentException("Delivery escrow envelope is incomplete");
    }
  }
}
