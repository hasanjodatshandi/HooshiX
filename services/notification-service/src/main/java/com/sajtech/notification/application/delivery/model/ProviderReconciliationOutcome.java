package com.sajtech.notification.application.delivery.model;

public record ProviderReconciliationOutcome(
    boolean liveProviderOutcome,
    ProviderReconciliationStatus status,
    String providerCode,
    String providerCorrelationId) {
  public ProviderReconciliationOutcome {
    if (liveProviderOutcome != (status != null)) {
      throw new IllegalArgumentException("Only live reconciliation outcomes carry status");
    }
    providerCode = bounded(providerCode, 64);
    providerCorrelationId = bounded(providerCorrelationId, 256);
  }

  public static ProviderReconciliationOutcome simulated() {
    return new ProviderReconciliationOutcome(false, null, null, null);
  }

  public static ProviderReconciliationOutcome live(
      ProviderReconciliationStatus status, String providerCode, String providerCorrelationId) {
    if (status == null) {
      throw new IllegalArgumentException("Reconciliation status is required");
    }
    return new ProviderReconciliationOutcome(true, status, providerCode, providerCorrelationId);
  }

  private static String bounded(String value, int maximum) {
    if (value == null) return null;
    if (value.length() > maximum || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Provider evidence is invalid");
    }
    return value;
  }
}
