package com.sajtech.notification.application.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderAttemptAction;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.Instant;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ProviderAttemptPlannerTest {
  private final ProviderAttemptPlanner planner =
      new ProviderAttemptPlanner(new ProviderRetryPolicy());
  private final RandomGenerator deterministic = new FixedRandomGenerator(0.5d);

  @Test
  void ambiguousOutcomeRequiresReconciliationAndNeverBlindRetry() {
    var decision =
        planner.plan(
            NotificationChannel.SMS,
            1,
            ProviderAttemptClassification.AMBIGUOUS,
            Instant.parse("2026-08-16T00:00:00Z"),
            Instant.parse("2026-08-16T00:02:00Z"),
            deterministic);

    assertThat(decision.action()).isEqualTo(ProviderAttemptAction.RECONCILE);
    assertThat(decision.retryDelay()).isNull();
  }

  @Test
  void transientFailureUsesBoundedRetryOnlyInsideDeliveryDeadline() {
    var retry =
        planner.plan(
            NotificationChannel.SMS,
            1,
            ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE,
            Instant.parse("2026-08-16T00:00:00Z"),
            Instant.parse("2026-08-16T00:02:00Z"),
            deterministic);
    var expired =
        planner.plan(
            NotificationChannel.SMS,
            1,
            ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE,
            Instant.parse("2026-08-16T00:01:59Z"),
            Instant.parse("2026-08-16T00:02:00Z"),
            deterministic);

    assertThat(retry.action()).isEqualTo(ProviderAttemptAction.RETRY_AFTER);
    assertThat(retry.retryDelay()).isNotNull();
    assertThat(expired.action()).isEqualTo(ProviderAttemptAction.EXPIRE);
  }

  @Test
  void closesObservationWindowAtChannelSpecificBound() {
    Instant accepted = Instant.parse("2026-08-16T00:00:00Z");
    assertThat(
            planner.observationWindowClosed(
                NotificationChannel.SMS,
                accepted,
                accepted.plus(NotificationChannel.SMS.observationWindow())))
        .isTrue();
    assertThat(
            planner.observationWindowClosed(
                NotificationChannel.EMAIL, accepted, accepted.plusSeconds(60)))
        .isFalse();
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
