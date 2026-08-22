package com.sajtech.notification.infrastructure.provider.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoggingProviderAdapterTest {
  @Test
  void localAdaptersNeverReturnCanonicalProviderClassification() {
    var email = new LoggingEmailProviderAdapter();
    var sms = new LoggingSmsProviderAdapter();
    Instant deadline = Instant.parse("2026-08-22T21:00:00Z");
    UUID emailAttempt = UUID.randomUUID();
    UUID emailExecution = UUID.randomUUID();
    UUID smsAttempt = UUID.randomUUID();
    UUID smsExecution = UUID.randomUUID();

    var emailOutcome =
        email.dispatch(
            new ProviderDispatchMessage(
                UUID.randomUUID(),
                emailAttempt,
                emailExecution,
                1,
                NotificationChannel.EMAIL,
                deadline,
                "person@example.com",
                "subject",
                "secret text",
                null));
    var smsOutcome =
        sms.dispatch(
            new ProviderDispatchMessage(
                UUID.randomUUID(),
                smsAttempt,
                smsExecution,
                1,
                NotificationChannel.SMS,
                deadline,
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
    assertThat(
            email
                .reconcile(
                    new ProviderReconciliationRequest(
                        UUID.randomUUID(),
                        emailAttempt,
                        emailExecution,
                        NotificationChannel.EMAIL,
                        null))
                .liveProviderOutcome())
        .isFalse();
    assertThat(
            sms.reconcile(
                    new ProviderReconciliationRequest(
                        UUID.randomUUID(), smsAttempt, smsExecution, NotificationChannel.SMS, null))
                .liveProviderOutcome())
        .isFalse();
  }
}
