package com.sajtech.notification.infrastructure.provider.liara;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.NotificationProviderConfiguration;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class LiaraSmtpProviderAdapterTest {
  @Test
  void senderRequiresAuthenticatedStartTlsAndFiniteTimeouts() {
    var sender = LiaraSmtpProviderAdapter.configuredSender(configuration());
    var properties = sender.getJavaMailProperties();

    assertThat(sender.getHost()).isEqualTo("smtp.fixture.invalid");
    assertThat(sender.getPort()).isEqualTo(587);
    assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("500");
    assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("1500");
    assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("1500");
  }

  @Test
  void authenticationFailureIsPermanentAndUnknownTransportFailureIsAmbiguous() {
    assertThat(
            LiaraSmtpProviderAdapter.classifyMailFailure(new MailAuthenticationException("fixture"))
                .classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
    assertThat(
            LiaraSmtpProviderAdapter.classifyMailFailure(new MailSendException("fixture"))
                .classification())
        .isEqualTo(ProviderAttemptClassification.AMBIGUOUS);
  }

  @Test
  void smtpHasNoFabricatedDeliveryEvidence() {
    var adapter = new LiaraSmtpProviderAdapter(configuration());
    var result =
        adapter.reconcile(
            new ProviderReconciliationRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                NotificationChannel.EMAIL,
                null));
    assertThat(result.status()).isEqualTo(ProviderReconciliationStatus.INCONCLUSIVE);
  }

  private static NotificationProviderConfiguration configuration() {
    return new NotificationProviderConfiguration(
        "smtp.fixture.invalid",
        587,
        "fixture-user",
        "fixture-value",
        URI.create("https://sms.fixture.invalid/v1"),
        "fixture-token-value",
        "+983000505");
  }
}
