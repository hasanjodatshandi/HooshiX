package com.sajtech.identity.configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "identity")
public record IdentityProperties(
    int grpcPort,
    boolean registrationRuntimeEnabled,
    boolean authenticationRuntimeEnabled,
    int maxConcurrentCallsPerConnection,
    boolean phoneRegistrationEnabled,
    String compromisedPasswordTarget,
    int compromisedPasswordMaxInFlight,
    String notificationTarget,
    boolean notificationDispatchEnabled,
    Path fingerprintKeyRingPath,
    Path challengeKeyRingPath,
    Path handoffKeyRingPath,
    Path refreshKeyRingPath,
    Duration keyRingMaximumStaleness,
    int argon2MaxConcurrentHashes,
    Quota quota,
    Jwt jwt) {
  public IdentityProperties {
    if (grpcPort <= 0 || grpcPort > 65535) {
      throw new IllegalArgumentException("Identity gRPC port is invalid");
    }
    if (maxConcurrentCallsPerConnection <= 0
        || compromisedPasswordMaxInFlight <= 0
        || argon2MaxConcurrentHashes <= 0) {
      throw new IllegalArgumentException("Identity concurrency configuration is invalid");
    }
    if (compromisedPasswordTarget == null
        || compromisedPasswordTarget.isBlank()
        || notificationTarget == null
        || notificationTarget.isBlank()) {
      throw new IllegalArgumentException("Identity dependency targets are required");
    }
    if (fingerprintKeyRingPath == null
        || challengeKeyRingPath == null
        || handoffKeyRingPath == null
        || refreshKeyRingPath == null
        || keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()
        || quota == null
        || jwt == null) {
      throw new IllegalArgumentException("Identity security configuration is invalid");
    }
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
          || hostTimeStatusPath == null) {
        throw new IllegalArgumentException("Identity quota configuration is invalid");
      }
    }
  }

  public record Jwt(
      Path privateKeyRingPath,
      Path publicVerifierBundlePath,
      String issuer,
      Set<String> allowedAudiences) {
    public Jwt {
      if (privateKeyRingPath == null
          || publicVerifierBundlePath == null
          || issuer == null
          || issuer.isBlank()
          || issuer.length() > 256
          || issuer.codePoints().anyMatch(Character::isISOControl)
          || allowedAudiences == null
          || allowedAudiences.isEmpty()) {
        throw new IllegalArgumentException("Identity JWT configuration is invalid");
      }
      allowedAudiences = Set.copyOf(allowedAudiences);
      if (allowedAudiences.stream()
          .anyMatch(
              audience ->
                  audience == null
                      || audience.isBlank()
                      || audience.length() > 128
                      || audience.contains("*")
                      || audience.codePoints().anyMatch(Character::isISOControl))) {
        throw new IllegalArgumentException("Identity JWT audience allow-list is invalid");
      }
    }
  }
}
