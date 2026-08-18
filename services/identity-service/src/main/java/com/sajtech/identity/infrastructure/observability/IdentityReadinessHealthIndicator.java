package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import org.jooq.DSLContext;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class IdentityReadinessHealthIndicator implements HealthIndicator {
  private final DSLContext dsl;
  private final RedisSemanticQuota quota;
  private final HostTimeHealth hostTime;
  private final FileBackedKeyRing[] rings;

  public IdentityReadinessHealthIndicator(
      DSLContext dsl,
      RedisSemanticQuota quota,
      HostTimeHealth hostTime,
      FileBackedKeyRing... rings) {
    this.dsl = dsl;
    this.quota = quota;
    this.hostTime = hostTime;
    this.rings = rings.clone();
  }

  @Override
  public Health health() {
    try {
      dsl.fetchOne("SELECT 1");
      for (FileBackedKeyRing ring : rings)
        if (!ring.isFresh()) return Health.down().withDetail("reason", "KEY_RING_STALE").build();
      if (!hostTime.synchronizedHealthy())
        return Health.down().withDetail("reason", "QUOTA_TIME_SOURCE_UNHEALTHY").build();
      if (!quota.connectivityHealthy())
        return Health.down().withDetail("reason", "QUOTA_REDIS_UNAVAILABLE").build();
      return Health.up().build();
    } catch (RuntimeException exception) {
      return Health.down().withDetail("reason", "IDENTITY_RUNTIME_DEPENDENCY_UNAVAILABLE").build();
    }
  }
}
