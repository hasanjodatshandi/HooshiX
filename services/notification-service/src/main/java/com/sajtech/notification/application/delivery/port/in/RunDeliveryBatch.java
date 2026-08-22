package com.sajtech.notification.application.delivery.port.in;

import com.sajtech.notification.application.delivery.model.DeliveryBatchResult;

public interface RunDeliveryBatch {
  DeliveryBatchResult run(int batchSize);
}
