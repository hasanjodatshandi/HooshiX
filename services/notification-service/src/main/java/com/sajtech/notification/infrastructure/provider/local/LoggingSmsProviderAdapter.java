package com.sajtech.notification.infrastructure.provider.local;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingSmsProviderAdapter implements NotificationProviderGateway {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSmsProviderAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.SMS;
  }

  @Override
  public boolean liveDelivery() {
    return false;
  }

  @Override
  public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
    requireSms(message);
    LOGGER
        .atInfo()
        .addKeyValue("eventCode", "NOTIFICATION_SMS_SIMULATED")
        .log("Local SMS notification simulation completed");
    return ProviderDispatchOutcome.simulated();
  }

  private static void requireSms(ProviderDispatchMessage message) {
    if (message == null || message.channel() != NotificationChannel.SMS) {
      throw new IllegalArgumentException("SMS adapter requires an SMS notification");
    }
  }
}
