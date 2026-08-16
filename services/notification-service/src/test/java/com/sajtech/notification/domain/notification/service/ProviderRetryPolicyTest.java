package com.sajtech.notification.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ProviderRetryPolicyTest {
  private final ProviderRetryPolicy policy = new ProviderRetryPolicy();

  @Test
  void retriesOnlyDefinitiveTransientFailuresWithinFourAttemptBudget() {
    assertThat(
            policy.shouldRetry(
                NotificationChannel.SMS,
                1,
                ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE))
        .isTrue();
    assertThat(
            policy.shouldRetry(
                NotificationChannel.SMS, 4, ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE))
        .isFalse();
    assertThat(
            policy.shouldRetry(
                NotificationChannel.SMS, 1, ProviderAttemptClassification.AMBIGUOUS))
        .isFalse();
    assertThat(policy.requiresReconciliation(ProviderAttemptClassification.AMBIGUOUS)).isTrue();
  }

  @Test
  void jitterStaysInsideDocumentedTwentyPercentEnvelope() {
    RandomGenerator minimum = new FixedRandomGenerator(0.0d);
    RandomGenerator maximum = new FixedRandomGenerator(Math.nextDown(1.0d));

    assertThat(policy.nextDelay(NotificationChannel.SMS, 1, minimum))
        .isEqualTo(Duration.ofMillis(1600));
    assertThat(policy.nextDelay(NotificationChannel.SMS, 1, maximum))
        .isLessThanOrEqualTo(Duration.ofMillis(2400));
  }

  private record FixedRandomGenerator(double value) implements RandomGenerator {
    @Override
    public long nextLong() {
      return Double.doubleToLongBits(value);
    }

    @Override
    public double nextDouble() {
      return value;
    }
  }
}
