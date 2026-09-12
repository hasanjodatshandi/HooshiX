package com.sajtech.notification.infrastructure.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

public final class FileBackedNotificationProviderConfiguration {
  private FileBackedNotificationProviderConfiguration() {}

  public static NotificationProviderConfiguration load(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      throw new IllegalStateException("Notification provider configuration file is unavailable");
    }
    Properties properties = new RejectingDuplicateProperties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to read Notification provider configuration", exception);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Notification provider configuration is invalid", exception);
    }
    try {
      EmailProviderKind kind =
          EmailProviderKind.valueOf(
              requiredTrimmed(properties, "email.provider").toUpperCase(Locale.ROOT));
      SmtpEmailProviderConfiguration email =
          switch (kind) {
            case GOOGLE_GMAIL -> googleGmail(properties);
            case GENERIC_SMTP -> genericSmtp(properties);
          };
      Set<String> expected =
          switch (kind) {
            case GOOGLE_GMAIL ->
                Set.of(
                    "email.provider",
                    "email.smtp.username",
                    "email.smtp.password",
                    "email.from-name",
                    "sms.provider",
                    "smsir.api-key",
                    "smsir.line-number");
            case GENERIC_SMTP ->
                Set.of(
                    "email.provider",
                    "email.smtp.host",
                    "email.smtp.port",
                    "email.smtp.username",
                    "email.smtp.password",
                    "email.from-address",
                    "email.from-name",
                    "sms.provider",
                    "smsir.api-key",
                    "smsir.line-number");
          };
      if (!properties.stringPropertyNames().equals(expected)) {
        throw new IllegalArgumentException("Provider configuration keys are invalid");
      }
      if (!"SMSIR".equals(requiredTrimmed(properties, "sms.provider").toUpperCase(Locale.ROOT))) {
        throw new IllegalArgumentException("SMS provider is invalid");
      }
      return new NotificationProviderConfiguration(
          email,
          SmsIrSmsProviderConfiguration.production(
              requiredSecret(properties, "smsir.api-key"),
              requiredTrimmed(properties, "smsir.line-number")));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Notification provider configuration is invalid", exception);
    }
  }

  private static SmtpEmailProviderConfiguration googleGmail(Properties properties) {
    return SmtpEmailProviderConfiguration.googleGmail(
        requiredTrimmed(properties, "email.smtp.username"),
        requiredSecret(properties, "email.smtp.password"),
        requiredTrimmed(properties, "email.from-name"));
  }

  private static SmtpEmailProviderConfiguration genericSmtp(Properties properties) {
    return new SmtpEmailProviderConfiguration(
        EmailProviderKind.GENERIC_SMTP,
        requiredTrimmed(properties, "email.smtp.host"),
        Integer.parseInt(requiredTrimmed(properties, "email.smtp.port")),
        requiredTrimmed(properties, "email.smtp.username"),
        requiredSecret(properties, "email.smtp.password"),
        requiredTrimmed(properties, "email.from-address"),
        requiredTrimmed(properties, "email.from-name"));
  }

  private static String requiredTrimmed(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Required provider configuration key is missing: " + key);
    }
    return value.trim();
  }

  private static String requiredSecret(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Required provider configuration key is missing: " + key);
    }
    return value;
  }

  private static final class RejectingDuplicateProperties extends Properties {
    @Override
    public synchronized Object put(Object key, Object value) {
      if (containsKey(key)) {
        throw new IllegalArgumentException("Duplicate provider configuration key");
      }
      return super.put(key, value);
    }
  }
}
