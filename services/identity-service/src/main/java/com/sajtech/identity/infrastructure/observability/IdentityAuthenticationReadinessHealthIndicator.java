package com.sajtech.identity.infrastructure.observability;

import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class IdentityAuthenticationReadinessHealthIndicator implements HealthIndicator {
  private final boolean enabled;
  private final BooleanSupplier refreshKeysFresh;
  private final BooleanSupplier signingKeysFresh;
  private final BooleanSupplier loginQuotaHealthy;

  public IdentityAuthenticationReadinessHealthIndicator(
      boolean enabled,
      BooleanSupplier refreshKeysFresh,
      BooleanSupplier signingKeysFresh,
      BooleanSupplier loginQuotaHealthy) {
    this.enabled = enabled;
    this.refreshKeysFresh = refreshKeysFresh;
    this.signingKeysFresh = signingKeysFresh;
    this.loginQuotaHealthy = loginQuotaHealthy;
  }

  @Override
  public Health health() {
    if (!enabled) return Health.up().withDetail("mode", "AUTHENTICATION_DISABLED").build();
    try {
      if (!refreshKeysFresh.getAsBoolean()) {
        return Health.down().withDetail("reason", "REFRESH_KEY_RING_STALE").build();
      }
      if (!signingKeysFresh.getAsBoolean()) {
        return Health.down().withDetail("reason", "JWT_SIGNING_KEY_RING_STALE").build();
      }
      if (!loginQuotaHealthy.getAsBoolean()) {
        return Health.down().withDetail("reason", "LOGIN_QUOTA_REDIS_UNAVAILABLE").build();
      }
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down().withDetail("reason", "AUTHENTICATION_RUNTIME_UNAVAILABLE").build();
    }
  }
}
