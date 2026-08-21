package com.sajtech.authorization.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "authorization")
public record AuthorizationProperties(
    int grpcPort,
    boolean runtimeEnabled,
    int maxConcurrentCallsPerConnection,
    CheckPermissionOverload checkPermissionOverload,
    Path fingerprintKeyRingPath,
    Path quotaKeyRingPath,
    Path identityJwtVerifierBundlePath,
    String identityJwtIssuer,
    Duration keyRingMaximumStaleness,
    Quota quota) {
  public AuthorizationProperties {
    if (grpcPort < 1
        || grpcPort > 65535
        || maxConcurrentCallsPerConnection < 1
        || checkPermissionOverload == null
        || fingerprintKeyRingPath == null
        || quotaKeyRingPath == null
        || identityJwtVerifierBundlePath == null
        || identityJwtIssuer == null
        || identityJwtIssuer.isBlank()
        || keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()
        || quota == null)
      throw new IllegalArgumentException("Authorization security configuration is invalid");
  }

  public record CheckPermissionOverload(
      int globalConcurrency,
      int perCallerConcurrency,
      int globalQueueCapacity,
      int perCallerQueueCapacity,
      int maxCallerBuckets,
      Duration queueWait) {
    public CheckPermissionOverload {
      if (globalConcurrency < 1
          || perCallerConcurrency < 1
          || perCallerConcurrency > globalConcurrency
          || globalQueueCapacity < 1
          || perCallerQueueCapacity < 1
          || perCallerQueueCapacity > globalQueueCapacity
          || maxCallerBuckets < 1
          || queueWait == null
          || queueWait.isZero()
          || queueWait.isNegative()
          || queueWait.compareTo(Duration.ofMillis(25)) > 0)
        throw new IllegalArgumentException(
            "Authorization CheckPermission overload configuration is invalid");
    }
  }

  public record Quota(
      String redisUri,
      int maxActiveBuckets,
      int maxNewBucketsPerMinute,
      int minimumMemoryHeadroomPercent,
      Path hostTimeStatusPath) {
    public Quota {
      if (redisUri == null
          || redisUri.isBlank()
          || maxActiveBuckets < 1
          || maxNewBucketsPerMinute < 1
          || minimumMemoryHeadroomPercent < 30
          || minimumMemoryHeadroomPercent >= 100
          || hostTimeStatusPath == null)
        throw new IllegalArgumentException("Authorization quota configuration is invalid");
    }
  }
}
