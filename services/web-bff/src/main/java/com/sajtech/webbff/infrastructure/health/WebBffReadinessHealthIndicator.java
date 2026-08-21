package com.sajtech.webbff.infrastructure.health;

import com.sajtech.webbff.configuration.WebBffProperties;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import java.util.*;
import org.springframework.boot.health.contributor.*;

public final class WebBffReadinessHealthIndicator implements HealthIndicator {
  private final WebBffProperties properties;
  private final List<FileBackedKeyRing> keyRings;
  private final RedisBffSessionRepository sessions;

  public WebBffReadinessHealthIndicator(
      WebBffProperties properties,
      List<FileBackedKeyRing> keyRings,
      RedisBffSessionRepository sessions) {
    this.properties = Objects.requireNonNull(properties);
    this.keyRings = List.copyOf(keyRings);
    this.sessions = Objects.requireNonNull(sessions);
  }

  @Override
  public Health health() {
    if (!properties.runtimeEnabled()) return down("runtime_disabled");
    if (keyRings.stream().anyMatch(k -> !k.isFresh())) return down("security_material_unavailable");
    if (!sessions.connectivityHealthy()) return down("security_redis_unavailable");
    return Health.up().build();
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}
