package com.sajtech.notification.application.delivery.port.out;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.domain.notification.model.NotificationChannel;

public interface NotificationProviderGateway {
  NotificationChannel channel();

  boolean liveDelivery();

  ProviderDispatchOutcome dispatch(ProviderDispatchMessage message);
}
