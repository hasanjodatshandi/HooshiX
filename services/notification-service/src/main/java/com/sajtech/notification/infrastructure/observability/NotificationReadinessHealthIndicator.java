package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import org.jooq.DSLContext;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

public final class NotificationReadinessHealthIndicator implements HealthIndicator {
  private final DSLContext dsl;
  private final FileBackedKeyRing keyRing;

  public NotificationReadinessHealthIndicator(DSLContext dsl, FileBackedKeyRing keyRing) {
    this.dsl = dsl;
    this.keyRing = keyRing;
  }

  @Override
  public Health health() {
    try {
      int result = dsl.fetchValue("select 1", int.class);
      if (result != 1) {
        return Health.down().withDetail("reason", "database-check-failed").build();
      }
    } catch (RuntimeException unavailable) {
      return Health.down().withDetail("reason", "database-unavailable").build();
    }
    if (!keyRing.isFresh()) {
      return Health.down().withDetail("reason", "key-ring-stale").build();
    }
    return Health.up().build();
  }
}
