package com.sajtech.notification.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
    int grpcPort,
    int maxConcurrentCallsPerConnection,
    String callerService,
    Path fingerprintKeyRingPath,
    Path deliveryKeyRingPath,
    Duration keyRingMaximumStaleness) {
  public NotificationProperties {
    if (grpcPort <= 0 || grpcPort > 65_535) {
      throw new IllegalArgumentException("Notification gRPC port is invalid");
    }
    if (maxConcurrentCallsPerConnection <= 0) {
      throw new IllegalArgumentException("Notification gRPC concurrency must be positive");
    }
    if (callerService == null || callerService.isBlank()) {
      throw new IllegalArgumentException("Notification caller service identity is required");
    }
    if (fingerprintKeyRingPath == null || deliveryKeyRingPath == null) {
      throw new IllegalArgumentException("Notification key-ring paths are required");
    }
    if (keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()) {
      throw new IllegalArgumentException("Notification key-ring staleness limit is invalid");
    }
  }
}
