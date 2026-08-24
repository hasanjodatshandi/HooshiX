package com.sajtech.authorization.infrastructure.runtime.grpc;

import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics;
import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics.ShedReason;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public final class CheckPermissionAdmissionController {
  private static final Pattern CALLER = Pattern.compile("[a-z][a-z0-9-]{0,63}");
  private final ReentrantLock lock = new ReentrantLock(true);
  private final Condition capacityChanged = lock.newCondition();
  private final Map<String, CallerState> callers = new HashMap<>();
  private final int globalLimit;
  private final int perCallerLimit;
  private final int globalQueueCapacity;
  private final int perCallerQueueCapacity;
  private final int maxCallerBuckets;
  private final long queueWaitNanos;
  private final AuthorizationCheckPermissionMetrics metrics;
  private int inFlight;
  private int queued;

  public CheckPermissionAdmissionController(
      int globalLimit,
      int perCallerLimit,
      int globalQueueCapacity,
      int perCallerQueueCapacity,
      int maxCallerBuckets,
      Duration queueWait,
      AuthorizationCheckPermissionMetrics metrics) {
    if (globalLimit < 1
        || perCallerLimit < 1
        || perCallerLimit > globalLimit
        || globalQueueCapacity < 1
        || perCallerQueueCapacity < 1
        || perCallerQueueCapacity > globalQueueCapacity
        || maxCallerBuckets < 1
        || queueWait == null
        || queueWait.isZero()
        || queueWait.isNegative()
        || queueWait.compareTo(Duration.ofMillis(25)) > 0)
      throw new IllegalArgumentException("CheckPermission overload configuration is invalid");
    this.globalLimit = globalLimit;
    this.perCallerLimit = perCallerLimit;
    this.globalQueueCapacity = globalQueueCapacity;
    this.perCallerQueueCapacity = perCallerQueueCapacity;
    this.maxCallerBuckets = maxCallerBuckets;
    this.queueWaitNanos = queueWait.toNanos();
    this.metrics = Objects.requireNonNull(metrics);
  }

  public Lease acquire(String callerId) {
    long started = System.nanoTime();
    if (callerId == null || !CALLER.matcher(callerId).matches())
      throw rejected(ShedReason.CALLER_CONTEXT, started);
    lock.lock();
    try {
      CallerState caller = callers.get(callerId);
      if (caller == null) {
        if (callers.size() >= maxCallerBuckets) throw rejected(ShedReason.CALLER_CONTEXT, started);
        caller = new CallerState();
        callers.put(callerId, caller);
      }
      if (hasCapacity(caller)) return admit(callerId, caller, started);
      if (queued >= globalQueueCapacity) {
        removeIdleCaller(callerId, caller);
        throw rejected(ShedReason.GLOBAL_QUEUE, started);
      }
      if (caller.queued >= perCallerQueueCapacity) {
        removeIdleCaller(callerId, caller);
        throw rejected(ShedReason.FAIR_SHARE_QUEUE, started);
      }
      queued++;
      caller.queued++;
      updateMetrics();
      boolean waiting = true;
      try {
        long remaining = queueWaitNanos;
        while (remaining > 0) {
          try {
            remaining = capacityChanged.awaitNanos(remaining);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            remaining = 0;
          }
          if (hasCapacity(caller)) {
            queued--;
            caller.queued--;
            waiting = false;
            return admit(callerId, caller, started);
          }
        }
        throw rejected(ShedReason.QUEUE_TIMEOUT, started);
      } finally {
        if (waiting) {
          queued--;
          caller.queued--;
          removeIdleCaller(callerId, caller);
          updateMetrics();
        }
      }
    } finally {
      lock.unlock();
    }
  }

  private AdmissionRejected rejected(ShedReason reason, long started) {
    updateMetricsIfLocked();
    metrics.shed(reason, System.nanoTime() - started);
    return new AdmissionRejected(reason);
  }

  private Lease admit(String callerId, CallerState caller, long started) {
    inFlight++;
    caller.inFlight++;
    updateMetrics();
    metrics.admitted(System.nanoTime() - started);
    return new Lease(this, callerId, caller);
  }

  private boolean hasCapacity(CallerState caller) {
    return inFlight < globalLimit && caller.inFlight < perCallerLimit;
  }

  private void release(String callerId, CallerState caller) {
    lock.lock();
    try {
      if (inFlight <= 0 || caller.inFlight <= 0)
        throw new IllegalStateException("CheckPermission admission lease is not active");
      inFlight--;
      caller.inFlight--;
      removeIdleCaller(callerId, caller);
      updateMetrics();
      capacityChanged.signalAll();
    } finally {
      lock.unlock();
    }
  }

  private void removeIdleCaller(String callerId, CallerState caller) {
    if (caller.inFlight == 0 && caller.queued == 0) callers.remove(callerId, caller);
  }

  private void updateMetricsIfLocked() {
    if (lock.isHeldByCurrentThread()) updateMetrics();
  }

  private void updateMetrics() {
    int maxCaller = 0;
    for (CallerState state : callers.values()) maxCaller = Math.max(maxCaller, state.inFlight);
    metrics.state(inFlight, queued, callers.size(), maxCaller);
  }

  public static final class Lease implements AutoCloseable {
    private final CheckPermissionAdmissionController owner;
    private final String callerId;
    private final CallerState caller;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Lease(CheckPermissionAdmissionController owner, String callerId, CallerState caller) {
      this.owner = owner;
      this.callerId = callerId;
      this.caller = caller;
    }

    @Override
    public void close() {
      if (closed.compareAndSet(false, true)) owner.release(callerId, caller);
    }
  }

  public static final class AdmissionRejected extends RuntimeException {
    private final ShedReason reason;

    AdmissionRejected(ShedReason reason) {
      super(reason.name(), null, false, false);
      this.reason = reason;
    }

    public ShedReason reason() {
      return reason;
    }
  }

  private static final class CallerState {
    private int inFlight;
    private int queued;
  }
}
