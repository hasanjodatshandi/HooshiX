package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.port.in.ErasureCoordination;
import com.sajtech.identity.application.erasure.port.in.RequestSelfErasureCommand;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedErasure implements ErasureCoordination {
  private final ErasureCoordination delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedErasure(
      ErasureCoordination delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.erasure.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public ErasureRequestView requestSelfErasure(RequestSelfErasureCommand command) {
    long started = System.nanoTime();
    String outcome = "INTERNAL";
    inFlight.incrementAndGet();
    Observation observation = start();
    try {
      ErasureRequestView result = delegate.requestSelfErasure(command);
      outcome = "SUCCESS";
      return result;
    } catch (ErasureException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stop(observation, outcome);
      timer(outcome, System.nanoTime() - started);
    }
  }

  private Observation start() {
    try {
      return Observation.start("identity.erasure", observations)
          .lowCardinalityKeyValue("operation", "REQUEST_SELF_ERASURE");
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stop(Observation observation, String outcome) {
    if (observation == null) return;
    try {
      observation.lowCardinalityKeyValue("outcome", outcome);
      observation.stop();
    } catch (RuntimeException ignored) {
    }
  }

  private void timer(String outcome, long elapsed) {
    try {
      Timer.builder("identity.erasure.duration")
          .tag("operation", "REQUEST_SELF_ERASURE")
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsed, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
