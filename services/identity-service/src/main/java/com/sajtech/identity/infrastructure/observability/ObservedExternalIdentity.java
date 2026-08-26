package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.externalidentity.ExternalIdentityException;
import com.sajtech.identity.application.externalidentity.model.ExternalIdentityEvidence;
import com.sajtech.identity.application.externalidentity.port.in.ExternalIdentityManagement;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ObservedExternalIdentity implements ExternalIdentityManagement {
  private final ExternalIdentityManagement delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedExternalIdentity(
      ExternalIdentityManagement delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.external_identity.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public AuthenticationSession establish(
      UUID requestId, ExternalIdentityEvidence evidence, byte[] clientAddress) {
    return observe("ESTABLISH", () -> delegate.establish(requestId, evidence, clientAddress));
  }

  @Override
  public AuthenticationSession link(
      UUID requestId,
      String refreshCredential,
      ExternalIdentityEvidence evidence,
      byte[] clientAddress) {
    return observe(
        "LINK", () -> delegate.link(requestId, refreshCredential, evidence, clientAddress));
  }

  @Override
  public AuthenticationSession unlink(UUID requestId, String refreshCredential, String issuer) {
    return observe("UNLINK", () -> delegate.unlink(requestId, refreshCredential, issuer));
  }

  @Override
  public boolean googleLinked(UUID requestId, String refreshCredential) {
    return observe("GET_STATUS", () -> delegate.googleLinked(requestId, refreshCredential));
  }

  private <T> T observe(String operation, Supplier<T> work) {
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    Observation observation = start(operation);
    String outcome = "INTERNAL";
    try {
      T value = work.get();
      outcome = "SUCCESS";
      return value;
    } catch (ExternalIdentityException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stop(observation, operation, outcome);
      record(operation, outcome, started);
    }
  }

  private Observation start(String operation) {
    try {
      Observation observation = Observation.start("identity.external_identity", observations);
      observation.lowCardinalityKeyValue("operation", operation);
      return observation;
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stop(Observation observation, String operation, String outcome) {
    if (observation == null) return;
    try {
      observation.lowCardinalityKeyValue("operation", operation);
      observation.lowCardinalityKeyValue("outcome", outcome);
      observation.stop();
    } catch (RuntimeException ignored) {
    }
  }

  private void record(String operation, String outcome, long started) {
    try {
      Timer.builder("identity.external_identity.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
