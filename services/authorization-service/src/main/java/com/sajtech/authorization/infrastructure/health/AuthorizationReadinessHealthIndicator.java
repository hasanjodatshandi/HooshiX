package com.sajtech.authorization.infrastructure.health;

import com.sajtech.authorization.configuration.AuthorizationProperties;
import com.sajtech.authorization.infrastructure.quota.*;
import com.sajtech.authorization.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import java.util.Objects;
import org.jooq.DSLContext;
import org.springframework.boot.health.contributor.*;

public final class AuthorizationReadinessHealthIndicator implements HealthIndicator {
  private final AuthorizationProperties properties;
  private final DSLContext dsl;
  private final FileBackedKeyRing intentKeys;
  private final FileBackedKeyRing quotaKeys;
  private final IdentityJwtVerifier jwt;
  private final RedisAdminQuota quota;
  private final ClockSafetyGuard clock;
  private final HostTimeHealth hostTime;

  public AuthorizationReadinessHealthIndicator(
      AuthorizationProperties properties,
      DSLContext dsl,
      FileBackedKeyRing intentKeys,
      FileBackedKeyRing quotaKeys,
      IdentityJwtVerifier jwt,
      RedisAdminQuota quota,
      ClockSafetyGuard clock,
      HostTimeHealth hostTime) {
    this.properties = Objects.requireNonNull(properties);
    this.dsl = Objects.requireNonNull(dsl);
    this.intentKeys = Objects.requireNonNull(intentKeys);
    this.quotaKeys = Objects.requireNonNull(quotaKeys);
    this.jwt = Objects.requireNonNull(jwt);
    this.quota = Objects.requireNonNull(quota);
    this.clock = Objects.requireNonNull(clock);
    this.hostTime = Objects.requireNonNull(hostTime);
  }

  @Override
  public Health health() {
    if (!properties.runtimeEnabled()) return down("runtime_disabled");
    if (!intentKeys.isFresh() || !quotaKeys.isFresh() || !jwt.isFresh())
      return down("security_material_unavailable");
    if (!clock.isHealthy(hostTime.synchronizedHealthy())) return down("time_source_unhealthy");
    if (!quota.connectivityHealthy()) return down("security_redis_unavailable");
    try {
      Object value = dsl.fetchValue("SELECT 1");
      if (!(value instanceof Number n) || n.intValue() != 1) return down("database_unavailable");
    } catch (RuntimeException e) {
      return down("database_unavailable");
    }
    return Health.up().build();
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}
