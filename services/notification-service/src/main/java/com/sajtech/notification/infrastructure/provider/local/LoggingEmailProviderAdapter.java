package com.sajtech.notification.infrastructure.provider.local;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local & !staging & !production")
public final class LoggingEmailProviderAdapter implements NotificationProviderGateway {
  private static final Logger LOGGER = LoggerFactory.getLogger(LoggingEmailProviderAdapter.class);

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public boolean liveDelivery() {
    return false;
  }

  @Override
  public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
    requireEmail(message);
    LOGGER
        .atInfo()
        .addKeyValue("eventCode", "NOTIFICATION_EMAIL_SIMULATED")
        .log("Local Email notification simulation completed");
    return ProviderDispatchOutcome.simulated();
  }

  @Override
  public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
    if (request == null || request.channel() != channel()) {
      throw new IllegalArgumentException("Local provider reconciliation channel is invalid");
    }
    return ProviderReconciliationOutcome.simulated();
  }

  private static void requireEmail(ProviderDispatchMessage message) {
    if (message == null || message.channel() != NotificationChannel.EMAIL) {
      throw new IllegalArgumentException("Email adapter requires an Email notification");
    }
  }
}
