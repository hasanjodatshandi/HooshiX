package com.sajtech.notification.infrastructure.provider.liara;

import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationRequest;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
import com.sajtech.notification.application.delivery.port.out.NotificationProviderGateway;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.provider.NotificationProviderConfiguration;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

public final class LiaraSmtpProviderAdapter implements NotificationProviderGateway {
  static final String FROM_ADDRESS = "no-reply@hooshix.com";
  static final String FROM_NAME = "Hooshix";
  private final JavaMailSenderImpl sender;

  public LiaraSmtpProviderAdapter(NotificationProviderConfiguration configuration) {
    this.sender = configuredSender(configuration);
  }

  @Override
  public NotificationChannel channel() {
    return NotificationChannel.EMAIL;
  }

  @Override
  public boolean liveDelivery() {
    return true;
  }

  @Override
  public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
    if (message == null || message.channel() != NotificationChannel.EMAIL) {
      throw new IllegalArgumentException("Liara SMTP accepts Email dispatch only");
    }
    try {
      MimeMessage mime = sender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(mime, true, StandardCharsets.UTF_8.name());
      helper.setFrom(FROM_ADDRESS, FROM_NAME);
      helper.setTo(message.recipient());
      helper.setSubject(message.subject());
      if (message.html() == null) {
        helper.setText(message.text(), false);
      } else {
        helper.setText(message.text(), message.html());
      }
      sender.send(mime);
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_ACCEPTED, "SMTP_250", null);
    } catch (MailAuthenticationException exception) {
      return classifyMailFailure(exception);
    } catch (MessagingException | UnsupportedEncodingException exception) {
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE,
          "SMTP_MESSAGE_REJECTED",
          null);
    } catch (MailException exception) {
      return classifyMailFailure(exception);
    }
  }

  @Override
  public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
    if (request == null || request.channel() != NotificationChannel.EMAIL) {
      throw new IllegalArgumentException("Liara SMTP reconciliation accepts Email only");
    }
    return ProviderReconciliationOutcome.live(
        ProviderReconciliationStatus.INCONCLUSIVE, null, request.providerCorrelationId());
  }

  static ProviderDispatchOutcome classifyMailFailure(MailException exception) {
    if (exception instanceof MailAuthenticationException) {
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_PERMANENT_FAILURE, "SMTP_AUTH_REJECTED", null);
    }
    return ProviderDispatchOutcome.live(ProviderAttemptClassification.AMBIGUOUS, null, null);
  }

  static JavaMailSenderImpl configuredSender(NotificationProviderConfiguration configuration) {
    JavaMailSenderImpl result = new JavaMailSenderImpl();
    result.setProtocol("smtp");
    result.setHost(configuration.smtpHost());
    result.setPort(configuration.smtpPort());
    result.setUsername(configuration.smtpUsername());
    result.setPassword(configuration.smtpPassword());
    result.setDefaultEncoding(StandardCharsets.UTF_8.name());
    Properties properties = result.getJavaMailProperties();
    properties.setProperty("mail.smtp.auth", "true");
    properties.setProperty("mail.smtp.starttls.enable", "true");
    properties.setProperty("mail.smtp.starttls.required", "true");
    properties.setProperty("mail.smtp.connectiontimeout", "500");
    properties.setProperty("mail.smtp.timeout", "1500");
    properties.setProperty("mail.smtp.writetimeout", "1500");
    properties.setProperty("mail.smtp.quitwait", "false");
    return result;
  }
}
