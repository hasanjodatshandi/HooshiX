package com.sajtech.notification.application.delivery.port.in;

import com.sajtech.notification.application.delivery.model.ReconciliationBatchResult;

public interface RunReconciliationBatch {
  ReconciliationBatchResult run(int batchSize);
}
