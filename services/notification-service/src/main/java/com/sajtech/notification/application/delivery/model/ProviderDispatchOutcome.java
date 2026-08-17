package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;

public record ProviderDispatchOutcome(
    boolean liveProviderOutcome,
    ProviderAttemptClassification classification,
    String providerCode,
    String providerCorrelationId) {
  private static final int MAX_PROVIDER_CODE_LENGTH = 64;
  private static final int MAX_CORRELATION_ID_LENGTH = 256;

  public ProviderDispatchOutcome {
    if (liveProviderOutcome != (classification != null)) {
      throw new IllegalArgumentException(
          "Only live provider outcomes may carry canonical provider classification");
    }
    providerCode = bounded(providerCode, MAX_PROVIDER_CODE_LENGTH, "provider code");
    providerCorrelationId =
        bounded(
            providerCorrelationId, MAX_CORRELATION_ID_LENGTH, "provider correlation identifier");
  }

  public static ProviderDispatchOutcome simulated() {
    return new ProviderDispatchOutcome(false, null, null, null);
  }

  public static ProviderDispatchOutcome live(
      ProviderAttemptClassification classification,
      String providerCode,
      String providerCorrelationId) {
    if (classification == null) {
      throw new IllegalArgumentException("Live provider classification is required");
    }
    return new ProviderDispatchOutcome(true, classification, providerCode, providerCorrelationId);
  }

  private static String bounded(String value, int maximum, String field) {
    if (value == null) {
      return null;
    }
    if (value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Invalid " + field);
    }
    return value;
  }
}
