package com.sajtech.notification.infrastructure.provider.google;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.infrastructure.provider.EmailProviderKind;
import com.sajtech.notification.infrastructure.provider.SmtpEmailProviderConfiguration;
import com.sajtech.notification.infrastructure.provider.smtp.SmtpEmailProviderAdapter;

public final class GoogleGmailSmtpProviderAdapter implements NotificationProviderGateway {
  private final SmtpEmailProviderAdapter delegate;

  public GoogleGmailSmtpProviderAdapter(SmtpEmailProviderConfiguration configuration) {
    if (configuration == null || configuration.provider() != EmailProviderKind.GOOGLE_GMAIL) {
      throw new IllegalArgumentException("Google Gmail SMTP configuration is required");
    }
    delegate = new SmtpEmailProviderAdapter(configuration);
  }

  @Override
  public NotificationChannel channel() {
    return delegate.channel();
  }

  @Override
  public boolean liveDelivery() {
    return delegate.liveDelivery();
  }

  @Override
  public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
    return delegate.dispatch(message);
  }

  @Override
  public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
    return delegate.reconcile(request);
  }
}
