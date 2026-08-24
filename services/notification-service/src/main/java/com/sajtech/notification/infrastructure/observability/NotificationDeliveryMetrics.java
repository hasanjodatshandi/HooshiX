package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.application.delivery.model.DeliveryBatchResult;
import com.sajtech.notification.application.delivery.model.ReconciliationBatchResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("EI_EXPOSE_REP2")
public final class NotificationDeliveryMetrics {
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public NotificationDeliveryMetrics(MeterRegistry meters) {
    this.meters = meters;
    Gauge.builder("notification.delivery.worker.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  public long startCycle() {
    inFlight.incrementAndGet();
    return System.nanoTime();
  }

  public void finishCycle(
      long started, DeliveryBatchResult delivery, ReconciliationBatchResult reconciliation) {
    inFlight.decrementAndGet();
    counter("notification.delivery.dispatch.claimed").increment(delivery.claimed());
    counter("notification.delivery.dispatch.completed").increment(delivery.completed());
    counter("notification.delivery.reconciliation.recovered")
        .increment(reconciliation.recoveredStale());
    counter("notification.delivery.reconciliation.claimed").increment(reconciliation.claimed());
    counter("notification.delivery.reconciliation.processed").increment(reconciliation.processed());
    timer(started);
  }

  public void cycleFailed(long started) {
    inFlight.decrementAndGet();
    counter("notification.delivery.worker.cycle.failures").increment();
    timer(started);
  }

  private Counter counter(String name) {
    return Counter.builder(name).register(meters);
  }

  private void timer(long started) {
    Timer.builder("notification.delivery.worker.cycle.duration")
        .register(meters)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
  }
}
