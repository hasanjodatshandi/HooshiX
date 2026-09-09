package com.sajtech.notification.infrastructure.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.EmailProviderKind;
import com.sajtech.notification.infrastructure.provider.SmtpEmailProviderConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class SmtpEmailProviderAdapterTest {
  @Test
  void senderRequiresAuthenticatedStartTlsHostnameVerificationAndFiniteTimeouts() {
    var sender = SmtpEmailProviderAdapter.configuredSender(configuration());
    var properties = sender.getJavaMailProperties();

    assertThat(sender.getHost()).isEqualTo("smtp.fixture.invalid");
    assertThat(sender.getPort()).isEqualTo(587);
    assertThat(properties.getProperty("mail.smtp.auth")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.ssl.checkserveridentity")).isEqualTo("true");
    assertThat(properties.getProperty("mail.smtp.ssl.protocols")).isEqualTo("TLSv1.3 TLSv1.2");
    assertThat(properties.getProperty("mail.smtp.connectiontimeout")).isEqualTo("500");
    assertThat(properties.getProperty("mail.smtp.timeout")).isEqualTo("1500");
    assertThat(properties.getProperty("mail.smtp.writetimeout")).isEqualTo("1500");
  }

  @Test
  void authenticationFailureIsPermanentAndUnknownTransportFailureIsAmbiguous() {
    assertThat(
            SmtpEmailProviderAdapter.classifyMailFailure(new MailAuthenticationException("fixture"))
                .classification())
        .isEqualTo(ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE);
    assertThat(
            SmtpEmailProviderAdapter.classifyMailFailure(new MailSendException("fixture"))
                .classification())
        .isEqualTo(ProviderAttemptClassification.AMBIGUOUS);
  }

  @Test
  void smtpHasNoFabricatedDeliveryEvidence() {
    var adapter = new SmtpEmailProviderAdapter(configuration());
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

  private static SmtpEmailProviderConfiguration configuration() {
    return new SmtpEmailProviderConfiguration(
        EmailProviderKind.GENERIC_SMTP,
        "smtp.fixture.invalid",
        587,
        "fixture-user",
        "fixture-value",
        "no-reply@fixture.invalid",
        "Hooshix");
  }
}
