package com.sajtech.authorization.infrastructure.observability;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class AuthorizationReservationMonitor implements SmartLifecycle {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(AuthorizationReservationMonitor.class);
  private final DSLContext dsl;
  private final AuthorizationSecurityMetrics metrics;
  private final Clock clock;
  private final AtomicBoolean failureReported = new AtomicBoolean();
  private ScheduledExecutorService scheduler;
  private volatile boolean running;

  public AuthorizationReservationMonitor(
      DSLContext dsl, AuthorizationSecurityMetrics metrics, Clock clock) {
    this.dsl = DSL.using(Objects.requireNonNull(dsl).configuration().derive());
    this.metrics = Objects.requireNonNull(metrics);
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public synchronized void start() {
    if (running) return;
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable ->
                Thread.ofVirtual().name("authorization-reservation-monitor").unstarted(runnable));
    running = true;
    scheduler.scheduleWithFixedDelay(this::sampleSafely, 0, 10, TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    if (!running) return;
    ScheduledExecutorService current = scheduler;
    scheduler = null;
    running = false;
    if (current != null) current.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  void sampleOnce() {
    var row =
        dsl.fetchOne(
            "SELECT p.created_at FROM authorization_idempotency_record p WHERE p.operation='PREPARE_REMOVAL' AND NOT EXISTS (SELECT 1 FROM authorization_idempotency_record r WHERE r.request_id=p.request_id AND r.operation IN ('FINALIZE_REMOVAL','CANCEL_REMOVAL')) ORDER BY p.created_at,p.request_id LIMIT 1");
    OffsetDateTime created = row == null ? null : row.get("created_at", OffsetDateTime.class);
    metrics.recordOldestReservation(created == null ? null : created.toInstant(), clock.instant());
  }

  private void sampleSafely() {
    try {
      sampleOnce();
      failureReported.set(false);
    } catch (RuntimeException failure) {
      if (failureReported.compareAndSet(false, true)) {
        LOGGER
            .atWarn()
            .addKeyValue("eventCode", "AUTHORIZATION_RESERVATION_MONITOR_FAILED")
            .log("Authorization reservation telemetry sample failed");
      }
    }
  }
}
