package com.sajtech.compromisedpassword.infrastructure.lookup.runtime;

import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public final class BoundedLookupCompromisedPasswords implements LookupCompromisedPasswords {
  private final LookupCompromisedPasswords delegate;
  private final Semaphore permits;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final ObservationRegistry observationRegistry;
  private final MeterRegistry meterRegistry;

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
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    meterRegistry.gauge("compromised_password.lookup.in_flight", inFlight);
  }

  @Override
  public List<CompromisedHashMatch> lookup(Sha1Prefix prefix) {
    if (!permits.tryAcquire()) {
      meterRegistry
          .counter("compromised_password.lookup.rejected", "reason", "capacity")
          .increment();
      throw new LookupCapacityExceededException();
    }

    inFlight.incrementAndGet();
    long started = System.nanoTime();
    String outcome = "success";
    Observation observation =
        Observation.start("compromised.password.lookup", observationRegistry)
            .lowCardinalityKeyValue("operation", "lookup");
    try (Observation.Scope ignored = observation.openScope()) {
      return delegate.lookup(prefix);
    } catch (RuntimeException exception) {
      outcome = "failure";
      observation.error(exception);
      throw exception;
    } finally {
      observation.stop();
      Timer.builder("compromised_password.lookup.duration")
          .tag("outcome", outcome)
          .register(meterRegistry)
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
      inFlight.decrementAndGet();
      permits.release();
    }
  }
}
