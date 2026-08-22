package com.sajtech.notification.infrastructure.runtime.result;

import com.sajtech.notification.application.delivery.port.out.DeliveryDatabaseTimePort;
import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;
import com.sajtech.notification.application.result.port.out.*;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

@SuppressWarnings("EI_EXPOSE_REP2")
public final class NotificationResultDispatcher implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationResultDispatcher.class);
  private static final int BATCH = 25;
  private static final Duration LEASE = Duration.ofSeconds(30);
  private static final Duration BUSY = Duration.ofMillis(250);
  private static final Duration IDLE = Duration.ofSeconds(1);
  private static final Duration MAX_AGE = Duration.ofDays(7);
  private final NotificationResultOutboxRepository outbox;
  private final NotificationResultCallbackPort callback;
  private final DeliveryDatabaseTimePort databaseTime;
  private final MeterRegistry meters;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("notification-result-dispatcher").factory());
  private volatile boolean running;

  public NotificationResultDispatcher(
      NotificationResultOutboxRepository outbox,
      NotificationResultCallbackPort callback,
      DeliveryDatabaseTimePort databaseTime,
      MeterRegistry meters) {
    this.outbox = outbox;
    this.callback = callback;
    this.databaseTime = databaseTime;
    this.meters = meters;
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
    if (running) executor.schedule(this::cycle, delay.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void cycle() {
    if (!running) return;
    boolean busy = false;
    try {
      List<NotificationResultOutboxRecord> records = outbox.claimDue(BATCH, LEASE);
      busy = !records.isEmpty();
      for (NotificationResultOutboxRecord record : records) process(record);
    } catch (RuntimeException ignored) {
      meters.counter("notification.result.dispatch.cycle.failures").increment();
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", "NOTIFICATION_RESULT_DISPATCH_CYCLE_FAILED")
          .log("Notification result dispatcher cycle failed");
    } finally {
      schedule(busy ? BUSY : IDLE);
    }
  }

  private void process(NotificationResultOutboxRecord record) {
    int attempts = record.attemptCount() + 1;
    Instant now = databaseTime.now();
    if (!now.isBefore(record.occurredAt().plus(MAX_AGE))) {
      outbox.markExhausted(record.outboxId(), attempts, "CALLBACK_AGE_EXHAUSTED");
      meters.counter("notification.result.dispatch.exhausted").increment();
      return;
    }
    try {
      callback.report(record);
      outbox.markCompleted(record.outboxId());
      meters.counter("notification.result.dispatch.completed").increment();
    } catch (StatusRuntimeException exception) {
      Status.Code code = exception.getStatus().getCode();
      if (permanent(code)) {
        outbox.markExhausted(record.outboxId(), attempts, "PERMANENT_" + code.name());
        meters.counter("notification.result.dispatch.exhausted").increment();
      } else {
        retry(record, attempts, "TRANSIENT_" + code.name());
      }
    } catch (RuntimeException ignored) {
      retry(record, attempts, "TRANSIENT_LOCAL");
    }
  }

  private void retry(NotificationResultOutboxRecord record, int attempts, String error) {
    Duration delay = backoff(attempts);
    Instant now = databaseTime.now();
    if (!now.plus(delay).isBefore(record.occurredAt().plus(MAX_AGE))) {
      outbox.markExhausted(record.outboxId(), attempts, "CALLBACK_AGE_EXHAUSTED");
      meters.counter("notification.result.dispatch.exhausted").increment();
    } else {
      outbox.reschedule(record.outboxId(), attempts, delay, error);
      meters.counter("notification.result.dispatch.retried").increment();
    }
  }

  private static boolean permanent(Status.Code code) {
    return code == Status.Code.INVALID_ARGUMENT
        || code == Status.Code.FAILED_PRECONDITION
        || code == Status.Code.PERMISSION_DENIED
        || code == Status.Code.UNAUTHENTICATED;
  }

  private static Duration backoff(int attempt) {
    return switch (attempt) {
      case 1 -> Duration.ofSeconds(1);
      case 2 -> Duration.ofSeconds(5);
      case 3 -> Duration.ofSeconds(30);
      case 4 -> Duration.ofMinutes(2);
      case 5 -> Duration.ofMinutes(10);
      case 6 -> Duration.ofMinutes(30);
      default -> Duration.ofHours(Math.min(6, Math.max(1, attempt - 5)));
    };
  }
}
