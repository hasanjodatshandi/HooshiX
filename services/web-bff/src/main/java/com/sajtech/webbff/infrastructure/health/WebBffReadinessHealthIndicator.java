package com.sajtech.webbff.infrastructure.health;

import com.sajtech.webbff.configuration.WebBffProperties;
import com.sajtech.webbff.infrastructure.quota.*;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import java.util.*;
import org.springframework.boot.health.contributor.*;

public final class WebBffReadinessHealthIndicator implements HealthIndicator {
  private final WebBffProperties properties;
  private final List<FileBackedKeyRing> keyRings;
  private final RedisBffSessionRepository sessions;
  private final RedisOidcQuota quota;
  private final OidcHostTimeHealth hostTime;

  public WebBffReadinessHealthIndicator(
      WebBffProperties properties,
      List<FileBackedKeyRing> keyRings,
      RedisBffSessionRepository sessions,
      RedisOidcQuota quota,
      OidcHostTimeHealth hostTime) {
    this.properties = Objects.requireNonNull(properties);
    this.keyRings = List.copyOf(keyRings);
    this.sessions = Objects.requireNonNull(sessions);
    this.quota = Objects.requireNonNull(quota);
    this.hostTime = Objects.requireNonNull(hostTime);
  }

  @Override
  public Health health() {
    if (!properties.runtimeEnabled()) return down("runtime_disabled");
    if (keyRings.stream().anyMatch(k -> !k.isFresh())) return down("security_material_unavailable");
    if (!sessions.connectivityHealthy()) return down("security_redis_unavailable");
    if (properties.googleOidcEnabled() && !quota.connectivityHealthy())
      return down("oidc_quota_redis_unavailable");
    if (properties.googleOidcEnabled() && !hostTime.synchronizedHealthy())
      return down("oidc_quota_time_unhealthy");
    return Health.up().build();
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}
