package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedPasswordHash implements PasswordHashPort {
  private final PasswordHashPort delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedPasswordHash(
      PasswordHashPort delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.password_hash.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public String hash(String password) {
    long start = System.nanoTime();
    inFlight.incrementAndGet();
    Observation o = observe();
    String outcome = "INTERNAL";
    try {
      String v = delegate.hash(password);
      outcome = "SUCCESS";
      return v;
    } catch (RegistrationException e) {
      outcome = e.error().name();
      throw e;
    } finally {
      inFlight.decrementAndGet();
      stop(o, outcome);
      try {
        Timer.builder("identity.password_hash.duration")
            .tag("outcome", outcome)
            .register(meters)
            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Observation observe() {
    try {
      return Observation.start("identity.password_hash", observations);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stop(Observation o, String outcome) {
    if (o == null) return;
    try {
      o.lowCardinalityKeyValue("outcome", outcome);
      o.stop();
    } catch (RuntimeException ignored) {
    }
  }
}
