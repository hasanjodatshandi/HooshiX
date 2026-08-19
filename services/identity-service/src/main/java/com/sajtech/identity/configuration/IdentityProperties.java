package com.sajtech.identity.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(
    int grpcPort,
    boolean registrationRuntimeEnabled,
    int maxConcurrentCallsPerConnection,
    boolean phoneRegistrationEnabled,
    String compromisedPasswordTarget,
    int compromisedPasswordMaxInFlight,
    String notificationTarget,
    boolean notificationDispatchEnabled,
    Path fingerprintKeyRingPath,
    Path challengeKeyRingPath,
    Path handoffKeyRingPath,
    Duration keyRingMaximumStaleness,
    int argon2MaxConcurrentHashes,
    Quota quota) {
  public IdentityProperties {
    if (grpcPort <= 0 || grpcPort > 65535)
      throw new IllegalArgumentException("Identity gRPC port is invalid");
    if (maxConcurrentCallsPerConnection <= 0
        || compromisedPasswordMaxInFlight <= 0
        || argon2MaxConcurrentHashes <= 0)
      throw new IllegalArgumentException("Identity concurrency configuration is invalid");
    if (compromisedPasswordTarget == null
        || compromisedPasswordTarget.isBlank()
        || notificationTarget == null
        || notificationTarget.isBlank())
      throw new IllegalArgumentException("Identity dependency targets are required");
    if (fingerprintKeyRingPath == null
        || challengeKeyRingPath == null
        || handoffKeyRingPath == null
        || keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()
        || quota == null)
      throw new IllegalArgumentException("Identity security configuration is invalid");
  }

  public record Quota(
      String redisUri,
      Path hmacKeyRingPath,
      int maxActiveBuckets,
      int maxNewBucketsPerMinute,
      int minimumMemoryHeadroomPercent,
      Path hostTimeStatusPath) {
    public Quota {
      if (redisUri == null
          || redisUri.isBlank()
          || hmacKeyRingPath == null
          || hostTimeStatusPath == null)
        throw new IllegalArgumentException("Identity quota configuration is invalid");
    }
  }
}
