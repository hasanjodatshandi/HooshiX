package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.application.tenant.TenantException;
import com.sajtech.identity.application.tenant.model.AcceptedInvitation;
import com.sajtech.identity.application.tenant.model.InvitationResult;
import com.sajtech.identity.application.tenant.model.SelectableTenantList;
import com.sajtech.identity.application.tenant.model.TenantCreation;
import com.sajtech.identity.application.tenant.model.TenantSelection;
import com.sajtech.identity.application.tenant.port.in.TenantLifecycle;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public final class ObservedTenantLifecycle implements TenantLifecycle {
  private final TenantLifecycle delegate;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ObservedTenantLifecycle(
      TenantLifecycle delegate, ObservationRegistry observations, MeterRegistry meters) {
    this.delegate = delegate;
    this.observations = observations;
    this.meters = meters;
    Gauge.builder("identity.tenant.in_flight", inFlight, AtomicInteger::get).register(meters);
  }

  @Override
  public TenantCreation createTenant(
      UUID requestId, String refreshCredential, String name, String slug) {
    return observe(
        "CREATE_TENANT", () -> delegate.createTenant(requestId, refreshCredential, name, slug));
  }

  @Override
  public SelectableTenantList listSelectable(String refreshCredential) {
    return observe("LIST_SELECTABLE_TENANTS", () -> delegate.listSelectable(refreshCredential));
  }

  @Override
  public TenantSelection selectTenant(
      UUID requestId, String refreshCredential, UUID membershipId, String audience) {
    return observe(
        "SELECT_TENANT",
        () -> delegate.selectTenant(requestId, refreshCredential, membershipId, audience));
  }

  @Override
  public InvitationResult inviteExistingUser(
      UUID requestId, String refreshCredential, UUID targetContactId) {
    return observe(
        "INVITE_EXISTING_USER",
        () -> delegate.inviteExistingUser(requestId, refreshCredential, targetContactId));
  }

  @Override
  public AcceptedInvitation acceptInvitation(
      UUID requestId, String refreshCredential, UUID invitationId) {
    return observe(
        "ACCEPT_INVITATION",
        () -> delegate.acceptInvitation(requestId, refreshCredential, invitationId));
  }

  @Override
  public void removeMembership(UUID requestId, String refreshCredential, UUID targetMembershipId) {
    observe(
        "REMOVE_MEMBERSHIP",
        () -> {
          delegate.removeMembership(requestId, refreshCredential, targetMembershipId);
          return Boolean.TRUE;
        });
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
    } catch (TenantException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stop(observation, outcome);
      record(operation, outcome, System.nanoTime() - started);
    }
  }

  private Observation start(String operation) {
    final Observation observation;
    try {
      observation = Observation.start("identity.tenant", observations);
    } catch (RuntimeException ignored) {
      return null;
    }
    try {
      observation.lowCardinalityKeyValue("operation", operation);
      return observation;
    } catch (RuntimeException ignored) {
      try {
        observation.stop();
      } catch (RuntimeException ignoredStopFailure) {
      }
      return null;
    }
  }

  private static void stop(Observation observation, String outcome) {
    if (observation == null) return;
    try {
      observation.lowCardinalityKeyValue("outcome", outcome);
    } catch (RuntimeException ignored) {
    }
    try {
      observation.stop();
    } catch (RuntimeException ignored) {
    }
  }

  private void record(String operation, String outcome, long elapsed) {
    try {
      Timer.builder("identity.tenant.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(elapsed, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }
}
