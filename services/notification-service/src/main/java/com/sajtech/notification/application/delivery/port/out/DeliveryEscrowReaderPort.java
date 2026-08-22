package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.DecryptedDeliveryPayload;
import java.util.UUID;

public interface DeliveryEscrowReaderPort {
  DecryptedDeliveryPayload decrypt(UUID notificationId, UUID attemptId);
}
