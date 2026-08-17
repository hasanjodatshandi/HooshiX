package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedSubmitNotification implements SubmitNotification {
  private final SubmitNotification delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedSubmitNotification(
      SubmitNotification delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("notification.submit.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public SubmitNotificationResult submit(SubmitNotificationCommand command) {
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    String outcome = "UNAVAILABLE";
    Observation observation = startObservation(command);
    try {
      SubmitNotificationResult result = delegate.submit(command);
      outcome = result.replay() ? "REPLAY" : "ACCEPTED";
      return result;
    } catch (NotificationSubmissionException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stopObservation(observation, outcome);
      recordTimer(command, outcome, System.nanoTime() - started);
    }
  }

  private Observation startObservation(SubmitNotificationCommand command) {
    try {
      return Observation.start("notification.submit", observations)
          .lowCardinalityKeyValue("channel", command.channel().name())
          .lowCardinalityKeyValue("semantic_type", command.semanticContent().semanticType().name());
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stopObservation(Observation observation, String outcome) {
    if (observation == null) {
      return;
    }
    try {
      observation.lowCardinalityKeyValue("outcome", outcome);
      observation.stop();
    } catch (RuntimeException ignored) {
      // Ordinary telemetry must not change notification submission behavior.
    }
  }

  private void recordTimer(SubmitNotificationCommand command, String outcome, long elapsedNanos) {
    try {
      Timer.builder("notification.submit.duration")
          .tag("channel", command.channel().name())
          .tag("semantic_type", command.semanticContent().semanticType().name())
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsedNanos, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
      // Ordinary telemetry must not change notification submission behavior.
    }
  }
}
