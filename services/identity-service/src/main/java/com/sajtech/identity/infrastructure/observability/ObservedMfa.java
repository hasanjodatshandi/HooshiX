package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.in.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ObservedMfa implements MfaManagement, CompleteMfaAuthentication {
  private final MfaManagement management;
  private final CompleteMfaAuthentication completion;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final Counter proofRejections;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedMfa(
      MfaManagement management,
      CompleteMfaAuthentication completion,
      ObservationRegistry observations,
      MeterRegistry meters) {
    this.management = management;
    this.completion = completion;
    this.observations = observations;
    this.meters = meters;
    this.proofRejections = Counter.builder("identity.mfa.proof_rejections").register(meters);
    Gauge.builder("identity.mfa.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public MfaStatus status(GetMfaStatusCommand command) {
    return observe("GET_STATUS", () -> management.status(command));
  }

  @Override
  public TotpEnrollmentStart startEnrollment(StartTotpEnrollmentCommand command) {
    return observe("START_ENROLLMENT", () -> management.startEnrollment(command));
  }

  @Override
  public MfaSessionMutation confirmEnrollment(ConfirmTotpEnrollmentCommand command) {
    return observe("CONFIRM_ENROLLMENT", () -> management.confirmEnrollment(command));
  }

  @Override
  public MfaSessionMutation disable(DisableTotpCommand command) {
    return observe("DISABLE_TOTP", () -> management.disable(command));
  }

  @Override
  public MfaSessionMutation rotateRecoveryCodes(RotateRecoveryCodesCommand command) {
    return observe("ROTATE_RECOVERY_CODES", () -> management.rotateRecoveryCodes(command));
  }

  @Override
  public AuthenticationSession complete(CompleteMfaAuthenticationCommand command) {
    return observe("COMPLETE_AUTHENTICATION", () -> completion.complete(command));
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
    } catch (MfaException exception) {
      outcome = exception.error().name();
      if (exception.error() == MfaError.INVALID_PROOF
          || exception.error() == MfaError.REPLAYED_PROOF) {
        safeIncrement(proofRejections);
      }
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stop(observation, operation, outcome);
      record(operation, outcome, started);
    }
  }

  private Observation start(String operation) {
    try {
      Observation observation = Observation.start("identity.mfa", observations);
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
      Timer.builder("identity.mfa.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }

  private static void safeIncrement(Counter counter) {
    try {
      counter.increment();
    } catch (RuntimeException ignored) {
    }
  }
}
