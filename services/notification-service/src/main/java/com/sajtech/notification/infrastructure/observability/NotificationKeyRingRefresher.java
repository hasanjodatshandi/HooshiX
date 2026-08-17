package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NotificationKeyRingRefresher implements Runnable {
  private final FileBackedKeyRing keyRing;
  private final Duration refreshInterval;
  private final Counter refreshFailures;
  private final AtomicBoolean started = new AtomicBoolean();

  public NotificationKeyRingRefresher(
      FileBackedKeyRing keyRing, MeterRegistry meterRegistry, Duration refreshInterval) {
    this.keyRing = keyRing;
    this.refreshInterval = refreshInterval;
    this.refreshFailures =
        Counter.builder("notification.keyring.refresh.failures").register(meterRegistry);
  }

  @Override
  public void run() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    Thread.ofVirtual().name("notification-keyring-refresh").start(this::loop);
  }

  private void loop() {
    while (!Thread.currentThread().isInterrupted()) {
      try {
        keyRing.reload();
        Thread.sleep(refreshInterval);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException refreshFailure) {
        refreshFailures.increment();
        try {
          Thread.sleep(refreshInterval);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
