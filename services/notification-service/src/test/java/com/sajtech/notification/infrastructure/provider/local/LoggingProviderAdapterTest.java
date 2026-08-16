package com.sajtech.notification.infrastructure.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoggingProviderAdapterTest {
  @Test
  void localAdaptersNeverReturnCanonicalProviderClassification() {
    var email = new LoggingEmailProviderAdapter();
    var sms = new LoggingSmsProviderAdapter();

    var emailOutcome =
        email.dispatch(
            new ProviderDispatchMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                "person@example.com",
                "subject",
                "secret text",
                null));
    var smsOutcome =
        sms.dispatch(
            new ProviderDispatchMessage(
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.SMS,
                "+989121234567",
                null,
                "secret text",
                null));

    assertThat(email.liveDelivery()).isFalse();
    assertThat(emailOutcome.liveProviderOutcome()).isFalse();
    assertThat(emailOutcome.classification()).isNull();
    assertThat(sms.liveDelivery()).isFalse();
    assertThat(smsOutcome.liveProviderOutcome()).isFalse();
    assertThat(smsOutcome.classification()).isNull();
  }
}
