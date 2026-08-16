package com.sajtech.notification.domain.notification.model;

import java.time.Duration;

public enum NotificationChannel {
  EMAIL(Duration.ofMinutes(5), Duration.ofHours(72), new long[] {5, 30, 120}),
  SMS(Duration.ofMinutes(2), Duration.ofHours(12), new long[] {2, 10, 30});

  private static final int MAX_PROVIDER_ATTEMPTS = 4;

  private final Duration deliveryDeadline;
  private final Duration observationWindow;
  private final long[] retryDelaySeconds;

  NotificationChannel(
      Duration deliveryDeadline, Duration observationWindow, long[] retryDelaySeconds) {
    this.deliveryDeadline = deliveryDeadline;
    this.observationWindow = observationWindow;
    this.retryDelaySeconds = retryDelaySeconds.clone();
  }

  public Duration deliveryDeadline() {
    return deliveryDeadline;
  }

  public Duration observationWindow() {
    return observationWindow;
  }

  public int maxProviderAttempts() {
    return MAX_PROVIDER_ATTEMPTS;
  }

  public Duration baseRetryDelayAfterAttempt(int completedAttemptNumber) {
    if (completedAttemptNumber < 1 || completedAttemptNumber >= MAX_PROVIDER_ATTEMPTS) {
      throw new IllegalArgumentException("Completed attempt number has no retry delay");
    }
    return Duration.ofSeconds(retryDelaySeconds[completedAttemptNumber - 1]);
  }
}
