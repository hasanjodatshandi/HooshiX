package com.sajtech.identity.infrastructure.worker;

import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.notification.port.out.NotificationOutboxStore;
import com.sajtech.identity.application.notification.port.out.NotificationSubmissionPort;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class NotificationOutboxDispatcher implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationOutboxDispatcher.class);
  private static final int BATCH = 32;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration BUSY = Duration.ofMillis(250);
  private static final Duration IDLE = Duration.ofSeconds(1);
  private static final Duration CUTOFF_MARGIN = Duration.ofSeconds(5);
  private final NotificationOutboxStore store;
  private final NotificationEscrowPort escrow;
  private final NotificationSubmissionPort notification;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-notification-dispatcher").factory());
  private volatile boolean running;

  public NotificationOutboxDispatcher(
      NotificationOutboxStore store,
      NotificationEscrowPort escrow,
      NotificationSubmissionPort notification,
      Clock clock) {
    this.store = store;
    this.escrow = escrow;
    this.notification = notification;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (running) return;
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
    if (!running) return;
    executor.schedule(this::cycle, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void cycle() {
    if (!running) return;
    boolean busy = false;
    try {
      busy = dispatchDue();
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", "IDENTITY_NOTIFICATION_DISPATCH_CYCLE_FAILED")
          .log("Identity Notification dispatcher cycle failed");
    } finally {
      schedule(busy ? BUSY : IDLE);
    }
  }

  boolean dispatchDue() {
    boolean claimed = false;
    for (int index = 0; index < BATCH; index++) {
      Instant now = clock.instant();
      List<NotificationOutboxRecord> records = store.claimDue(now, 1, LEASE);
      if (records.isEmpty()) break;
      if (records.size() != 1) {
        throw new IllegalStateException("Notification outbox exceeded the single-claim lease");
      }
      claimed = true;
      process(records.getFirst());
    }
    return claimed;
  }

  private void process(NotificationOutboxRecord record) {
    Instant now = clock.instant();
    if (!now.isBefore(record.messageNotAfter().minus(CUTOFF_MARGIN))) {
      store.markPermanentFailure(record.outboxId(), now, "CUTOFF");
      return;
    }
    try {
      var handoff =
          escrow.decrypt(
              record.outboxId(), record.escrowKeyId(), record.nonce(), record.ciphertext());
      UUID notificationId = notification.submit(record, handoff);
      store.markSubmitted(record.outboxId(), notificationId, clock.instant());
    } catch (StatusRuntimeException exception) {
      handleGrpc(record, exception.getStatus().getCode());
    } catch (IllegalStateException exception) {
      store.markPermanentFailure(record.outboxId(), clock.instant(), "LOCAL_HANDOFF_INVALID");
      LOGGER
          .atError()
          .addKeyValue("eventCode", "IDENTITY_NOTIFICATION_HANDOFF_PERMANENT_FAILURE")
          .log("Identity Notification handoff failed permanently");
    }
  }

  private void handleGrpc(NotificationOutboxRecord record, Status.Code code) {
    if (code == Status.Code.UNAVAILABLE
        || code == Status.Code.DEADLINE_EXCEEDED
        || code == Status.Code.RESOURCE_EXHAUSTED
        || code == Status.Code.ABORTED
        || code == Status.Code.INTERNAL) {
      Instant now = clock.instant();
      int attempts = record.attemptCount() + 1;
      Instant next = now.plus(backoff(attempts));
      if (!next.isBefore(record.messageNotAfter().minus(CUTOFF_MARGIN)))
        store.markPermanentFailure(record.outboxId(), now, "CUTOFF");
      else store.reschedule(record.outboxId(), attempts, next, now, "TRANSIENT_" + code.name());
      return;
    }
    store.markPermanentFailure(record.outboxId(), clock.instant(), "PERMANENT_" + code.name());
    LOGGER
        .atError()
        .addKeyValue("eventCode", "IDENTITY_NOTIFICATION_HANDOFF_PERMANENT_FAILURE")
        .addKeyValue("dependencyOutcome", code.name())
        .log("Identity Notification handoff failed permanently");
  }

  private static Duration backoff(int attempt) {
    long base =
        switch (attempt) {
          case 1 -> 1000;
          case 2 -> 2000;
          case 3 -> 5000;
          case 4 -> 10000;
          default -> Math.min(30000, 10000L + (attempt - 4L) * 5000L);
        };
    long spread = Math.max(1, base / 5);
    long jitter = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
    return Duration.ofMillis(Math.max(1, base + jitter));
  }
}
