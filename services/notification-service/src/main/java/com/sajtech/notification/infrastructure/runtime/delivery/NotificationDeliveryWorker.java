package com.sajtech.notification.infrastructure.runtime.delivery;

import com.sajtech.notification.application.delivery.model.DeliveryBatchResult;
import com.sajtech.notification.application.delivery.model.ReconciliationBatchResult;
import com.sajtech.notification.application.delivery.port.in.RunDeliveryBatch;
import com.sajtech.notification.application.delivery.port.in.RunReconciliationBatch;
import com.sajtech.notification.infrastructure.observability.NotificationDeliveryMetrics;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class NotificationDeliveryWorker implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
  private static final int BATCH = 25;
  private static final Duration BUSY = Duration.ofMillis(250);
  private static final Duration IDLE = Duration.ofSeconds(1);
  private final RunDeliveryBatch delivery;
  private final RunReconciliationBatch reconciliation;
  private final NotificationDeliveryMetrics metrics;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("notification-delivery-worker").factory());
  private volatile boolean running;

  public NotificationDeliveryWorker(
      RunDeliveryBatch delivery,
      RunReconciliationBatch reconciliation,
      NotificationDeliveryMetrics metrics) {
    this.delivery = delivery;
    this.reconciliation = reconciliation;
    this.metrics = metrics;
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
    long started = metrics.startCycle();
    boolean busy = false;
    try {
      DeliveryBatchResult sent = delivery.run(BATCH);
      ReconciliationBatchResult reconciled = reconciliation.run(BATCH);
      metrics.finishCycle(started, sent, reconciled);
      busy = sent.claimed() > 0 || reconciled.recoveredStale() > 0 || reconciled.claimed() > 0;
    } catch (RuntimeException ignored) {
      metrics.cycleFailed(started);
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", "NOTIFICATION_DELIVERY_CYCLE_FAILED")
          .log("Notification delivery worker cycle failed");
    } finally {
      schedule(busy ? BUSY : IDLE);
    }
  }
}
