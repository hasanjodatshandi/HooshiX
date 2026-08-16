package com.sajtech.compromisedpassword.infrastructure.lookup.runtime;

import com.sajtech.compromisedpassword.application.lookup.LookupOverloadedException;
import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BoundedLookupCompromisedPasswords implements LookupCompromisedPasswords {
  private static final Logger LOGGER = LoggerFactory.getLogger(BoundedLookupCompromisedPasswords.class);
  private static final String TELEMETRY_FAILURE_EVENT = "compromised_password_telemetry_failure";

  private final LookupCompromisedPasswords delegate;
  private final Semaphore permits;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicBoolean telemetryFailureReported = new AtomicBoolean();
  private final ObservationRegistry observationRegistry;
  private final Counter capacityRejected;
  private final Timer successTimer;
  private final Timer failureTimer;

  public BoundedLookupCompromisedPasswords(
      LookupCompromisedPasswords delegate,
      int maxConcurrentLookups,
      ObservationRegistry observationRegistry,
      MeterRegistry meterRegistry) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    if (maxConcurrentLookups <= 0) {
      throw new IllegalArgumentException("Maximum concurrent lookups must be positive");
    }
    this.permits = new Semaphore(maxConcurrentLookups, true);
    this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
    Objects.requireNonNull(meterRegistry, "meterRegistry");
    meterRegistry.gauge("compromised_password.lookup.in_flight", inFlight);
    capacityRejected =
        Counter.builder("compromised_password.lookup.rejected")
            .tag("reason", "capacity")
            .register(meterRegistry);
    successTimer =
        Timer.builder("compromised_password.lookup.duration")
            .tag("outcome", "success")
            .register(meterRegistry);
    failureTimer =
        Timer.builder("compromised_password.lookup.duration")
            .tag("outcome", "failure")
            .register(meterRegistry);
  }

  @Override
  public List<CompromisedHashMatch> lookup(Sha1Prefix prefix) {
    if (!permits.tryAcquire()) {
      incrementSafely(capacityRejected);
      throw new LookupOverloadedException();
    }

    inFlight.incrementAndGet();
    long started = System.nanoTime();
    boolean succeeded = false;
    Observation observation = startObservationSafely();
    Observation.Scope scope = openScopeSafely(observation);
    try {
      List<CompromisedHashMatch> matches = delegate.lookup(prefix);
      succeeded = true;
      return matches;
    } finally {
      inFlight.decrementAndGet();
      permits.release();
      closeScopeSafely(scope);
      stopObservationSafely(observation);
      recordSafely(
          succeeded ? successTimer : failureTimer,
          System.nanoTime() - started,
          TimeUnit.NANOSECONDS);
    }
  }

  private Observation startObservationSafely() {
    try {
      Observation observation =
          Observation.start("compromised.password.lookup", observationRegistry);
      observation.lowCardinalityKeyValue("operation", "lookup");
      return observation;
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
      return null;
    }
  }

  private Observation.Scope openScopeSafely(Observation observation) {
    if (observation == null) {
      return null;
    }
    try {
      return observation.openScope();
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
      return null;
    }
  }

  private void closeScopeSafely(Observation.Scope scope) {
    if (scope == null) {
      return;
    }
    try {
      scope.close();
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
    }
  }

  private void stopObservationSafely(Observation observation) {
    if (observation == null) {
      return;
    }
    try {
      observation.stop();
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
    }
  }

  private void incrementSafely(Counter counter) {
    try {
      counter.increment();
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
    }
  }

  private void recordSafely(Timer timer, long duration, TimeUnit timeUnit) {
    try {
      timer.record(duration, timeUnit);
    } catch (RuntimeException exception) {
      reportTelemetryFailure();
    }
  }

  private void reportTelemetryFailure() {
    if (telemetryFailureReported.compareAndSet(false, true)) {
      LOGGER.warn("event={} telemetry instrumentation failed", TELEMETRY_FAILURE_EVENT);
    }
  }
}
