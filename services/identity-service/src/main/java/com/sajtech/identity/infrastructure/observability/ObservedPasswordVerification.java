package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.port.out.PasswordVerificationPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedPasswordVerification implements PasswordVerificationPort {
  private final PasswordVerificationPort delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedPasswordVerification(
      PasswordVerificationPort delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.password_verify.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public boolean matches(String normalizedPassword, String encodedHash) {
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    Observation observation = startObservation();
    String outcome = "INTERNAL";
    try {
      boolean matched = delegate.matches(normalizedPassword, encodedHash);
      outcome = matched ? "MATCHED" : "REJECTED";
      return matched;
    } catch (AuthenticationException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stopObservation(observation, outcome);
      try {
        Timer.builder("identity.password_verify.duration")
            .tag("outcome", outcome)
            .register(meters)
            .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
      } catch (RuntimeException ignored) {
      }
    }
  }

  private Observation startObservation() {
    try {
      return Observation.start("identity.password_verify", observations);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stopObservation(Observation observation, String outcome) {
    if (observation == null) return;
    try {
      observation.lowCardinalityKeyValue("outcome", outcome);
      observation.stop();
    } catch (RuntimeException ignored) {
    }
  }
}
