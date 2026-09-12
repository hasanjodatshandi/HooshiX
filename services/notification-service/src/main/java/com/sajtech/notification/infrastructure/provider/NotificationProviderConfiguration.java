package com.sajtech.notification.infrastructure.provider;

public record NotificationProviderConfiguration(
    SmtpEmailProviderConfiguration email, SmsIrSmsProviderConfiguration sms) {
  public NotificationProviderConfiguration {
    if (email == null || sms == null) {
      throw new IllegalArgumentException("Email and SMS provider configurations are required");
    }
  }
}
