package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.profile.ProfileException;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import io.micrometer.core.instrument.*;
import io.micrometer.observation.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ObservedProfileManagement implements ProfileManagement {
  private final ProfileManagement delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedProfileManagement(
      ProfileManagement delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.profile.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public ProfileContactStore.ProfileRecord profile(String refreshCredential) {
    return run("GET_PROFILE", () -> delegate.profile(refreshCredential));
  }

  @Override
  public void update(
      String refreshCredential,
      UUID requestId,
      String firstName,
      String lastName,
      String fatherName) {
    run(
        "UPDATE_PROFILE",
        () -> {
          delegate.update(refreshCredential, requestId, firstName, lastName, fatherName);
          return null;
        });
  }

  @Override
  public List<ProfileContactStore.ContactRecord> contacts(String refreshCredential) {
    return run("LIST_CONTACTS", () -> delegate.contacts(refreshCredential));
  }

  @Override
  public UUID addContact(
      String refreshCredential, UUID requestId, String type, String value, String locale) {
    return run(
        "ADD_CONTACT",
        () -> delegate.addContact(refreshCredential, requestId, type, value, locale));
  }

  @Override
  public boolean resendContactVerification(
      String refreshCredential, UUID requestId, UUID contactId) {
    return run(
        "RESEND_CONTACT_VERIFICATION",
        () -> delegate.resendContactVerification(refreshCredential, requestId, contactId));
  }

  @Override
  public boolean verifyContact(
      String refreshCredential, UUID requestId, UUID contactId, String code) {
    return run(
        "VERIFY_CONTACT",
        () -> delegate.verifyContact(refreshCredential, requestId, contactId, code));
  }

  @Override
  public boolean primary(String refreshCredential, UUID requestId, UUID contactId) {
    return run(
        "SET_PRIMARY_CONTACT", () -> delegate.primary(refreshCredential, requestId, contactId));
  }

  @Override
  public boolean remove(String refreshCredential, UUID requestId, UUID contactId) {
    return run("REMOVE_CONTACT", () -> delegate.remove(refreshCredential, requestId, contactId));
  }

  private <T> T run(String operation, Callable<T> action) {
    long started = System.nanoTime();
    String outcome = "INTERNAL";
    inFlight.incrementAndGet();
    Observation observation = start(operation);
    try {
      T result = action.call();
      outcome = "SUCCESS";
      return result;
    } catch (ProfileException exception) {
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
      return Observation.start("identity.profile", observations)
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
      Timer.builder("identity.profile.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsed, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
