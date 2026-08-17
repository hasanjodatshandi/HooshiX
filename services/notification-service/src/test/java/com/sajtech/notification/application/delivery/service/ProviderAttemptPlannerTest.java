package com.sajtech.notification.application.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.ProviderAttemptAction;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderOutcomeClassification;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.Instant;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ProviderAttemptPlannerTest {
  private static final RandomGenerator DETERMINISTIC_RANDOM = new ZeroRandom();
  private final ProviderAttemptPlanner planner =
      new ProviderAttemptPlanner(new ProviderRetryPolicy());

  @Test
  void ambiguousOutcomeAlwaysReconciles() {
    var decision =
        planner.afterOutcome(
            NotificationChannel.EMAIL,
            1,
            ProviderOutcomeClassification.AMBIGUOUS,
            Instant.parse("2026-08-16T00:00:00Z"),
            Instant.parse("2026-08-16T00:10:00Z"),
            DETERMINISTIC_RANDOM);

    assertThat(decision.action()).isEqualTo(ProviderAttemptAction.RECONCILE);
  }

  @Test
  void transientFailureSchedulesBoundedRetryBeforeDeadline() {
    Instant now = Instant.parse("2026-08-16T00:00:00Z");
    var decision =
        planner.afterOutcome(
            NotificationChannel.SMS,
            1,
            ProviderOutcomeClassification.DEFINITIVE_TRANSIENT_FAILURE,
            now,
            now.plusSeconds(60),
            DETERMINISTIC_RANDOM);

    assertThat(decision.action()).isEqualTo(ProviderAttemptAction.RETRY);
    assertThat(decision.retryAt()).isAfter(now).isBefore(now.plusSeconds(60));
  }

  @Test
  void retryThatWouldMissDeadlineExpiresInstead() {
    Instant now = Instant.parse("2026-08-16T00:00:00Z");
    var decision =
        planner.afterOutcome(
            NotificationChannel.EMAIL,
            1,
            ProviderOutcomeClassification.DEFINITIVE_TRANSIENT_FAILURE,
            now,
            now.plusMillis(1),
            DETERMINISTIC_RANDOM);

    assertThat(decision.action()).isEqualTo(ProviderAttemptAction.EXPIRE);
  }

  private static final class ZeroRandom extends java.util.Random {
    @Override
    public long nextLong(long bound) {
      return 0;
    }
  }
}
