package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import java.time.Duration;

public interface DeliveryExecutionRepository {
  void recordOutcome(ProviderDispatchMessage message, ProviderDispatchOutcome outcome);

  void scheduleRetry(ProviderDispatchMessage message, Duration delay);

  void markPermanentFailure(ProviderDispatchMessage message);
}
