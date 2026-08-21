package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.in.*;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedRegistration
    implements RegisterLocal, ResendRegistrationVerification, ConfirmRegistration {
  private final RegisterLocal register;
  private final ResendRegistrationVerification resend;
  private final ConfirmRegistration confirm;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedRegistration(
      RegisterLocal register,
      ResendRegistrationVerification resend,
      ConfirmRegistration confirm,
      ObservationRegistry observations,
      MeterRegistry meters) {
    this.register = register;
    this.resend = resend;
    this.confirm = confirm;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.registration.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public void register(RegisterLocalCommand command) {
    run(
        "REGISTER",
        () -> {
          register.register(command);
          return null;
        });
  }

  @Override
  public void resend(ResendRegistrationCommand command) {
    run(
        "RESEND_REGISTRATION_VERIFICATION",
        () -> {
          resend.resend(command);
          return null;
        });
  }

  @Override
  public boolean confirm(ConfirmRegistrationCommand command) {
    return run("CONFIRM_REGISTRATION", () -> confirm.confirm(command));
  }

  private <T> T run(String operation, java.util.concurrent.Callable<T> action) {
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    String outcome = "INTERNAL";
    Observation observation = start(operation);
    try {
      T value = action.call();
      outcome = "SUCCESS";
      return value;
    } catch (RegistrationException exception) {
      outcome = exception.error().name();
      throw exception;
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception impossible) {
      throw new IllegalStateException(impossible);
    } finally {
      inFlight.decrementAndGet();
      stop(observation, outcome);
      timer(operation, outcome, System.nanoTime() - started);
    }
  }

  private Observation start(String operation) {
    try {
      return Observation.start("identity.registration", observations)
          .lowCardinalityKeyValue("operation", operation);
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

  private void timer(String operation, String outcome, long elapsed) {
    try {
      Timer.builder("identity.registration.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsed, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
