package com.sajtech.identity.infrastructure.worker;

import com.sajtech.identity.application.notification.port.out.NotificationOutboxStore;
import com.sajtech.identity.application.registration.port.out.RegistrationStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class IdentityRetentionWorker implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(IdentityRetentionWorker.class);
  private static final int BATCH = 128;
  private static final Duration INTERVAL = Duration.ofMinutes(1);
  private static final Duration DEDUP_RETENTION = Duration.ofDays(35);

  private final NotificationOutboxStore outboxStore;
  private final RegistrationStore registrationStore;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-retention").factory());
  private volatile boolean running;

  public IdentityRetentionWorker(
      NotificationOutboxStore outboxStore, RegistrationStore registrationStore, Clock clock) {
    this.outboxStore = outboxStore;
    this.registrationStore = registrationStore;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    schedule(Duration.ZERO);
  }

  @Override
  public synchronized void stop() {
    running = false;
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void schedule(Duration delay) {
    if (!running) {
      return;
    }
    executor.schedule(this::cycle, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void cycle() {
    try {
      Instant now = clock.instant();
      int erased = outboxStore.eraseExpiredSensitive(now, BATCH);
      registrationStore.deleteDedupBefore(now.minus(DEDUP_RETENTION), BATCH);
      if (erased > 0) {
        LOGGER
            .atWarn()
            .addKeyValue("eventCode", "IDENTITY_HANDOFF_RETENTION_ENFORCED")
            .addKeyValue("erasedCount", erased)
            .log("Expired Identity handoff ciphertext was erased");
      }
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", "IDENTITY_RETENTION_CYCLE_FAILED")
          .log("Identity retention cycle failed");
    } finally {
      schedule(INTERVAL);
    }
  }
}
