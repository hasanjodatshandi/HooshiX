package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.DecryptedDeliveryPayload;
import com.sajtech.notification.application.delivery.model.DeliveryEscrowEnvelope;

public interface DeliveryEscrowReaderPort {
  DecryptedDeliveryPayload decrypt(DeliveryEscrowEnvelope envelope);
}
