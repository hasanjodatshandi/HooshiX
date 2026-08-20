package com.sajtech.identity.infrastructure.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class AuthorizationOutboxMetrics {
  private static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(10);
  private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();
  private final Counter provisionOwnerFailures;
  private final Counter provisionMemberFailures;
  private final Counter tenantLifecycleFailures;
  private final Counter finalizeRemovalFailures;
  private final Counter cancelRemovalFailures;
  private final Counter unknownFailures;
  private Instant nextSampleAt = Instant.MIN;

  public AuthorizationOutboxMetrics(MeterRegistry meters) {
    Objects.requireNonNull(meters);
    Gauge.builder(
            "identity.authorization.outbox.oldest_pending_age",
            oldestPendingAgeSeconds,
            AtomicLong::get)
        .baseUnit("seconds")
        .register(meters);
    provisionOwnerFailures = counter(meters, "PROVISION_OWNER");
    provisionMemberFailures = counter(meters, "PROVISION_MEMBER");
    tenantLifecycleFailures = counter(meters, "APPLY_TENANT_LIFECYCLE");
    finalizeRemovalFailures = counter(meters, "FINALIZE_MEMBERSHIP_REMOVAL");
    cancelRemovalFailures = counter(meters, "CANCEL_MEMBERSHIP_REMOVAL");
    unknownFailures = counter(meters, "UNKNOWN");
  }

  public boolean sampleDue(Instant now) {
    Objects.requireNonNull(now);
    if (now.isBefore(nextSampleAt)) return false;
    nextSampleAt = now.plus(SAMPLE_INTERVAL);
    return true;
  }

  public void recordOldestPending(Instant createdAt, Instant now) {
    Objects.requireNonNull(now);
    if (createdAt == null) {
      oldestPendingAgeSeconds.set(0);
      return;
    }
    oldestPendingAgeSeconds.set(Math.max(0, Duration.between(createdAt, now).toSeconds()));
  }

  public void definitiveFailure(String operation) {
    try {
      counter(operation).increment();
    } catch (RuntimeException ignored) {
      // Ordinary telemetry failure must not change durable outbox processing.
    }
  }

  private Counter counter(String operation) {
    return switch (operation) {
      case "PROVISION_OWNER" -> provisionOwnerFailures;
      case "PROVISION_MEMBER" -> provisionMemberFailures;
      case "APPLY_TENANT_LIFECYCLE" -> tenantLifecycleFailures;
      case "FINALIZE_MEMBERSHIP_REMOVAL" -> finalizeRemovalFailures;
      case "CANCEL_MEMBERSHIP_REMOVAL" -> cancelRemovalFailures;
      default -> unknownFailures;
    };
  }

  private static Counter counter(MeterRegistry meters, String operation) {
    return Counter.builder("identity.authorization.outbox.definitive_failures")
        .tag("operation", operation)
        .register(meters);
  }
}
