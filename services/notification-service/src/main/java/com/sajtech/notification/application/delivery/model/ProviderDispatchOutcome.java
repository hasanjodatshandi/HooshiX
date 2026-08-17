package com.sajtech.notification.application.delivery.model;

import com.sajtech.notification.domain.notification.model.ProviderOutcomeClassification;

public record ProviderDispatchOutcome(
    boolean dispatched,
    ProviderOutcomeClassification classification,
    String providerCode,
    String providerCorrelationId) {
  private static final int MAX_PROVIDER_CODE_LENGTH = 128;
  private static final int MAX_CORRELATION_ID_LENGTH = 256;

  public ProviderDispatchOutcome {
    if (dispatched && classification == null) {
      throw new IllegalArgumentException("Dispatched provider outcome requires classification");
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
      ProviderOutcomeClassification classification,
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
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > maximum) {
      throw new IllegalArgumentException(field + " exceeds maximum length");
    }
    return trimmed;
  }
}
