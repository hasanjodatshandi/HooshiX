package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.registration.RegistrationException;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedPasswordLifecycle
    implements ChangePassword, RequestPasswordRecovery, ConfirmPasswordRecovery {
  private final ChangePassword change;
  private final RequestPasswordRecovery request;
  private final ConfirmPasswordRecovery confirm;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedPasswordLifecycle(
      ChangePassword change,
      RequestPasswordRecovery request,
      ConfirmPasswordRecovery confirm,
      ObservationRegistry observations,
      MeterRegistry meters) {
    this.change = change;
    this.request = request;
    this.confirm = confirm;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.password_lifecycle.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public PasswordChangeSession change(ChangePasswordCommand command) {
    return run("CHANGE", () -> change.change(command));
  }

  @Override
  public void request(RequestPasswordRecoveryCommand command) {
    run(
        "REQUEST_RECOVERY",
        () -> {
          request.request(command);
          return null;
        });
  }

  @Override
  public void confirm(ConfirmPasswordRecoveryCommand command) {
    run(
        "CONFIRM_RECOVERY",
        () -> {
          confirm.confirm(command);
          return null;
        });
  }

  private <T> T run(String operation, java.util.concurrent.Callable<T> action) {
    long started = System.nanoTime();
    String outcome = "INTERNAL";
    inFlight.incrementAndGet();
    Observation observation = start(operation);
    try {
      T result = action.call();
      outcome = "SUCCESS";
      return result;
    } catch (PasswordException exception) {
      outcome = exception.error().name();
      throw exception;
    } catch (RegistrationException exception) {
      outcome = exception.error().name();
      throw exception;
    } catch (AuthenticationException exception) {
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
      return Observation.start("identity.password_lifecycle", observations)
          .lowCardinalityKeyValue("operation", operation);
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

  private void timer(String operation, String outcome, long elapsed) {
    try {
      Timer.builder("identity.password_lifecycle.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsed, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
