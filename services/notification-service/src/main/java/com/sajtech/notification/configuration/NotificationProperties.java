package com.sajtech.notification.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification")
public record NotificationProperties(
    int grpcPort,
    int maxConcurrentCallsPerConnection,
    String callerService,
    boolean deliveryRuntimeEnabled,
    String identityResultTarget,
    Path providerConfigurationPath,
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
    if (callerService == null
        || callerService.isBlank()
        || identityResultTarget == null
        || identityResultTarget.isBlank()) {
      throw new IllegalArgumentException(
          "Notification service identity and callback target are required");
    }
    if (providerConfigurationPath == null
        || fingerprintKeyRingPath == null
        || deliveryKeyRingPath == null) {
      throw new IllegalArgumentException(
          "Notification provider/key configuration paths are required");
    }
    if (keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()) {
      throw new IllegalArgumentException("Notification key-ring staleness limit is invalid");
    }
  }
}
