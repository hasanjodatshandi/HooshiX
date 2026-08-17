package com.sajtech.notification.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ProviderRetryPolicyTest {
  private static final RandomGenerator ZERO_RANDOM = new ZeroRandom();
  private final ProviderRetryPolicy retryPolicy = new ProviderRetryPolicy();

  @Test
  void emailRetryScheduleMatchesBoundedArchitecture() {
    assertThat(retryPolicy.nextDelay(NotificationChannel.EMAIL, 1, ZERO_RANDOM))
        .contains(Duration.ofMillis(400));
    assertThat(retryPolicy.nextDelay(NotificationChannel.EMAIL, 2, ZERO_RANDOM))
        .contains(Duration.ofMillis(1200));
    assertThat(retryPolicy.nextDelay(NotificationChannel.EMAIL, 3, ZERO_RANDOM)).isEmpty();
  }

  @Test
  void smsRetryScheduleMatchesBoundedArchitecture() {
    assertThat(retryPolicy.nextDelay(NotificationChannel.SMS, 1, ZERO_RANDOM))
        .contains(Duration.ofMillis(250));
    assertThat(retryPolicy.nextDelay(NotificationChannel.SMS, 2, ZERO_RANDOM))
        .contains(Duration.ofMillis(750));
    assertThat(retryPolicy.nextDelay(NotificationChannel.SMS, 3, ZERO_RANDOM)).isEmpty();
  }

  private static final class ZeroRandom extends java.util.Random {
    @Override
    public long nextLong(long bound) {
      return 0;
    }
  }
}
