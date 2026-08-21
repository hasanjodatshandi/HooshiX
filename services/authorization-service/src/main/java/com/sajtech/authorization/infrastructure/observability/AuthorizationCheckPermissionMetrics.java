package com.sajtech.authorization.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthorizationCheckPermissionMetrics {
  public enum ShedReason {
    GLOBAL_QUEUE,
    FAIR_SHARE_QUEUE,
    QUEUE_TIMEOUT,
    CALLER_CONTEXT
  }

  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger queueDepth = new AtomicInteger();
  private final AtomicInteger callerBuckets = new AtomicInteger();
  private final AtomicInteger maxCallerInFlight = new AtomicInteger();
  private final Timer admittedQueueWait;
  private final Timer shedQueueWait;
  private final Map<ShedReason, Counter> shedCounters;

  public AuthorizationCheckPermissionMetrics(MeterRegistry meters) {
    Objects.requireNonNull(meters);
    Gauge.builder("authorization.check_permission.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
    Gauge.builder("authorization.check_permission.queue_depth", queueDepth, AtomicInteger::get)
        .register(meters);
    Gauge.builder(
            "authorization.check_permission.caller_buckets", callerBuckets, AtomicInteger::get)
        .register(meters);
    Gauge.builder(
            "authorization.check_permission.max_caller_in_flight",
            maxCallerInFlight,
            AtomicInteger::get)
        .register(meters);
    admittedQueueWait = queueWait(meters, "admitted");
    shedQueueWait = queueWait(meters, "shed");
    EnumMap<ShedReason, Counter> counters = new EnumMap<>(ShedReason.class);
    for (ShedReason reason : ShedReason.values())
      counters.put(
          reason,
          Counter.builder("authorization.check_permission.shed")
              .tag("reason", reason.name().toLowerCase(java.util.Locale.ROOT))
              .register(meters));
    shedCounters = Map.copyOf(counters);
  }

  public void state(
      int currentInFlight, int currentQueueDepth, int bucketCount, int currentMaxCaller) {
    inFlight.set(currentInFlight);
    queueDepth.set(currentQueueDepth);
    callerBuckets.set(bucketCount);
    maxCallerInFlight.set(currentMaxCaller);
  }

  public void admitted(long waitNanos) {
    record(admittedQueueWait, waitNanos);
  }

  public void shed(ShedReason reason, long waitNanos) {
    try {
      shedCounters.get(Objects.requireNonNull(reason)).increment();
    } catch (RuntimeException ignored) {
    }
    record(shedQueueWait, waitNanos);
  }

  private static Timer queueWait(MeterRegistry meters, String outcome) {
    return Timer.builder("authorization.check_permission.queue_wait")
        .tag("outcome", outcome)
        .register(meters);
  }

  private static void record(Timer timer, long waitNanos) {
    try {
      timer.record(Math.max(0, waitNanos), TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
