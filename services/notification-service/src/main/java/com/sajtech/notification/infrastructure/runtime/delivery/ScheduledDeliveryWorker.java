package com.sajtech.notification.infrastructure.runtime.delivery;

import com.sajtech.notification.application.delivery.port.in.RunDeliveryBatch;

public final class ScheduledDeliveryWorker {
  private final RunDeliveryBatch deliveryBatch;

  public ScheduledDeliveryWorker(RunDeliveryBatch deliveryBatch) {
    this.deliveryBatch = deliveryBatch;
  }

  public void execute() {
    deliveryBatch.run(100);
  }
}
