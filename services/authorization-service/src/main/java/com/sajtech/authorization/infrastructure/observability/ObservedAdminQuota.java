package com.sajtech.authorization.infrastructure.observability;

import com.sajtech.authorization.application.AuthorizationException;
import com.sajtech.authorization.application.model.ActorContext;
import com.sajtech.authorization.application.port.out.AdminQuota;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class ObservedAdminQuota implements AdminQuota {
  private final AdminQuota delegate;
  private final MeterRegistry meters;

  public ObservedAdminQuota(AdminQuota delegate, MeterRegistry meters) {
    this.delegate = Objects.requireNonNull(delegate);
    this.meters = Objects.requireNonNull(meters);
  }

  @Override
  public void acquire(ActorContext actor, int cost) {
    long started = System.nanoTime();
    String outcome = "unavailable";
    try {
      delegate.acquire(actor, cost);
      outcome = "allowed";
    } catch (AuthorizationException e) {
      outcome =
          switch (e.error()) {
            case QUOTA_EXCEEDED -> "denied";
            case QUOTA_UNAVAILABLE -> "unavailable";
            default -> "rejected";
          };
      throw e;
    } finally {
      Timer.builder("authorization.admin_quota.duration")
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }
  }
}
