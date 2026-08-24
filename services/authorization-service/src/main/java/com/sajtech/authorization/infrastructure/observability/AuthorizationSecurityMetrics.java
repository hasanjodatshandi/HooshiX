package com.sajtech.authorization.infrastructure.observability;

import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class AuthorizationSecurityMetrics implements AuthorizationSecurityTelemetry {
  private final Counter auditFailures;
  private final AtomicLong oldestReservationAgeSeconds = new AtomicLong();

  public AuthorizationSecurityMetrics(MeterRegistry meters) {
    Objects.requireNonNull(meters);
    auditFailures = Counter.builder("authorization.security.audit_failures").register(meters);
    Gauge.builder(
            "authorization.owner_reservation.oldest_unresolved_age",
            oldestReservationAgeSeconds,
            AtomicLong::get)
        .baseUnit("seconds")
        .register(meters);
  }

  @Override
  public void auditFailure() {
    try {
      auditFailures.increment();
    } catch (RuntimeException ignored) {
    }
  }

  public void recordOldestReservation(Instant createdAt, Instant now) {
    Objects.requireNonNull(now);
    oldestReservationAgeSeconds.set(
        createdAt == null ? 0 : Math.max(0, Duration.between(createdAt, now).toSeconds()));
  }
}
