package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ObservedAuthentication
    implements AuthenticateLocal,
        RefreshSession,
        LogoutCurrent,
        LogoutAll,
        IssueAudienceAccessToken {
  private final AuthenticateLocal authenticate;
  private final RefreshSession refresh;
  private final LogoutCurrent logoutCurrent;
  private final LogoutAll logoutAll;
  private final IssueAudienceAccessToken issueAccessToken;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final Counter refreshReuse;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedAuthentication(
      AuthenticateLocal authenticate,
      RefreshSession refresh,
      LogoutCurrent logoutCurrent,
      LogoutAll logoutAll,
      IssueAudienceAccessToken issueAccessToken,
      ObservationRegistry observations,
      MeterRegistry meters) {
    this.authenticate = authenticate;
    this.refresh = refresh;
    this.logoutCurrent = logoutCurrent;
    this.logoutAll = logoutAll;
    this.issueAccessToken = issueAccessToken;
    this.observations = observations;
    this.meters = meters;
    this.refreshReuse = Counter.builder("identity.refresh.reuse").register(meters);
    Gauge.builder("identity.authentication.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public AuthenticationSession authenticate(AuthenticateLocalCommand command) {
    return observe("LOGIN", () -> authenticate.authenticate(command));
  }

  @Override
  public AuthenticationSession refresh(RefreshSessionCommand command) {
    return observe("REFRESH", () -> refresh.refresh(command));
  }

  @Override
  public void logout(LogoutCurrentCommand command) {
    observeVoid("LOGOUT_CURRENT", () -> logoutCurrent.logout(command));
  }

  @Override
  public void logoutAll(LogoutAllCommand command) {
    observeVoid("LOGOUT_ALL", () -> logoutAll.logoutAll(command));
  }

  @Override
  public SignedAccessToken issue(IssueAudienceAccessTokenCommand command) {
    return observe("ISSUE_ACCESS_TOKEN", () -> issueAccessToken.issue(command));
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
    } catch (AuthenticationException exception) {
      outcome = exception.error().name();
      if (exception.error() == AuthenticationError.REFRESH_REUSE_DETECTED) {
        safeIncrement(refreshReuse);
      }
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stop(observation, operation, outcome);
      record(operation, outcome, started);
    }
  }

  private void observeVoid(String operation, Runnable work) {
    observe(
        operation,
        () -> {
          work.run();
          return Boolean.TRUE;
        });
  }

  private Observation start(String operation) {
    try {
      Observation observation = Observation.start("identity.authentication", observations);
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
      Timer.builder("identity.authentication.duration")
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
