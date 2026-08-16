package com.sajtech.notification.domain.notification.service;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import java.time.Duration;
import java.util.random.RandomGenerator;

public final class ProviderRetryPolicy {
  private static final double JITTER_FRACTION = 0.20d;

  public boolean shouldRetry(
      NotificationChannel channel,
      int completedAttemptNumber,
      ProviderAttemptClassification classification) {
    return classification == ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE
        && completedAttemptNumber < channel.maxProviderAttempts();
  }

  public Duration nextDelay(
      NotificationChannel channel, int completedAttemptNumber, RandomGenerator random) {
    Duration base = channel.baseRetryDelayAfterAttempt(completedAttemptNumber);
    double factor = 1.0d + ((random.nextDouble() * 2.0d) - 1.0d) * JITTER_FRACTION;
    long millis = Math.max(1L, Math.round(base.toMillis() * factor));
    return Duration.ofMillis(millis);
  }

  public boolean requiresReconciliation(ProviderAttemptClassification classification) {
    return classification == ProviderAttemptClassification.AMBIGUOUS;
  }
}
