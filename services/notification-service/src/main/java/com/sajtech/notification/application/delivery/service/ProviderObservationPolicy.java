package com.sajtech.notification.application.delivery.service;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Duration;

public final class ProviderObservationPolicy {
  private static final Duration[] SMS_DELAYS = {
    Duration.ofSeconds(15),
    Duration.ofSeconds(30),
    Duration.ofMinutes(1),
    Duration.ofMinutes(2),
    Duration.ofMinutes(5),
    Duration.ofMinutes(15)
  };
  private static final Duration[] EMAIL_DELAYS = {
    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30)
  };

  public Duration nextDelay(NotificationChannel channel, Duration elapsed) {
    if (channel == null || elapsed == null || elapsed.isNegative()) {
      throw new IllegalArgumentException("Observation scheduling input is invalid");
    }
    Duration[] schedule = channel == NotificationChannel.SMS ? SMS_DELAYS : EMAIL_DELAYS;
    Duration cumulative = Duration.ZERO;
    for (Duration delay : schedule) {
      cumulative = cumulative.plus(delay);
      if (elapsed.compareTo(cumulative) < 0) return delay;
    }
    return schedule[schedule.length - 1];
  }
}
