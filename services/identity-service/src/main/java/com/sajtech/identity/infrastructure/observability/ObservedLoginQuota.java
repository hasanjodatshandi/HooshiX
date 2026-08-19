package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.port.out.LoginQuotaPort;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;

public final class ObservedLoginQuota implements LoginQuotaPort {
  private final LoginQuotaPort delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;

  public ObservedLoginQuota(
      LoginQuotaPort delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
  }

  @Override
  public void checkSource(byte[] clientAddress) {
    observe("SOURCE", () -> delegate.checkSource(clientAddress));
  }

  @Override
  public void recordFailure(CanonicalContact contact) {
    observe("FAILURE", () -> delegate.recordFailure(contact));
  }

  @Override
  public void recordSuccess(CanonicalContact contact) {
    observe("SUCCESS_RESET", () -> delegate.recordSuccess(contact));
  }

  private void observe(String operation, Runnable action) {
    long started = System.nanoTime();
    Observation observation = start(operation);
    String outcome = "INTERNAL";
    try {
      action.run();
      outcome = "SUCCESS";
    } catch (AuthenticationException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      stop(observation, operation, outcome);
      record(operation, outcome, started);
    }
  }

  private Observation start(String operation) {
    try {
      Observation observation = Observation.start("identity.login_quota", observations);
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
      Timer.builder("identity.login_quota.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
