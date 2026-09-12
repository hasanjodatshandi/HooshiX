package com.sajtech.notification.infrastructure.provider;

import java.net.URI;

public record SmsIrSmsProviderConfiguration(URI baseUri, String apiKey, long lineNumber) {
  public static final URI API_BASE_URI = URI.create("https://api.sms.ir");

  public SmsIrSmsProviderConfiguration {
    if (!API_BASE_URI.equals(baseUri)) {
      throw new IllegalArgumentException("SMS.ir API base URI is fixed");
    }
    apiKey = requireText(apiKey, "SMS.ir API key", 512);
    if (lineNumber < 100 || lineNumber > 999_999_999_999_999_999L) {
      throw new IllegalArgumentException("SMS.ir line number is invalid");
    }
  }

  public static SmsIrSmsProviderConfiguration production(String apiKey, String lineNumber) {
    if (lineNumber == null || !lineNumber.matches("[1-9][0-9]{2,17}")) {
      throw new IllegalArgumentException("SMS.ir line number is invalid");
    }
    try {
      return new SmsIrSmsProviderConfiguration(API_BASE_URI, apiKey, Long.parseLong(lineNumber));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("SMS.ir line number is invalid", exception);
    }
  }

  private static String requireText(String value, String name, int maximum) {
    if (value == null
        || value.isBlank()
        || value.length() > maximum
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return value.trim();
  }
}
