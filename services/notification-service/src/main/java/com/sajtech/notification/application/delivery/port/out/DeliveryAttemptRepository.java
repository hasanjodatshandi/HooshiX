package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import java.util.List;

public interface DeliveryAttemptRepository {
  List<ProviderDispatchMessage> claimDue(int batchSize);

  void markCompleted(ProviderDispatchMessage message);
}
