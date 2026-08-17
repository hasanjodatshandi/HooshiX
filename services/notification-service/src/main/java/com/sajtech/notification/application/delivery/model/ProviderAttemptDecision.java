package com.sajtech.notification.application.delivery.model;

import java.time.Duration;

public record ProviderAttemptDecision(ProviderAttemptAction action, Duration retryDelay) {
  public ProviderAttemptDecision {
    if (action == null) {
      throw new IllegalArgumentException("Provider attempt action is required");
    }
    if ((action == ProviderAttemptAction.RETRY_AFTER) != (retryDelay != null)) {
      throw new IllegalArgumentException("Only retry decisions carry a retry delay");
    }
  }

  public static ProviderAttemptDecision action(ProviderAttemptAction action) {
    return new ProviderAttemptDecision(action, null);
  }

  public static ProviderAttemptDecision retryAfter(Duration delay) {
    return new ProviderAttemptDecision(ProviderAttemptAction.RETRY_AFTER, delay);
  }
}
