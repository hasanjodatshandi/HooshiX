package com.sajtech.conversation.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conversation")
public record ConversationProperties(
    boolean providerRuntimeEnabled,
    Path contentKeyRingPath,
    Duration keyRingRefreshInterval,
    Duration keyRingMaximumStaleness,
    String authorizationTarget,
    int authorizationMaximumConcurrentChecks,
    Path identityJwtVerifierBundlePath,
    String identityJwtIssuer,
    Duration jwtVerifierRefreshInterval,
    Duration jwtVerifierMaximumStaleness) {
  public ConversationProperties {
    if (contentKeyRingPath == null
        || keyRingRefreshInterval == null
        || keyRingRefreshInterval.isZero()
        || keyRingRefreshInterval.isNegative()
        || keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()
        || keyRingRefreshInterval.compareTo(keyRingMaximumStaleness) >= 0
        || authorizationTarget == null
        || authorizationTarget.isBlank()
        || authorizationMaximumConcurrentChecks < 1
        || identityJwtVerifierBundlePath == null
        || identityJwtIssuer == null
        || identityJwtIssuer.isBlank()
        || identityJwtIssuer.length() > 256
        || identityJwtIssuer.codePoints().anyMatch(Character::isISOControl)
        || jwtVerifierRefreshInterval == null
        || jwtVerifierRefreshInterval.isZero()
        || jwtVerifierRefreshInterval.isNegative()
        || jwtVerifierMaximumStaleness == null
        || jwtVerifierMaximumStaleness.isZero()
        || jwtVerifierMaximumStaleness.isNegative()
        || jwtVerifierRefreshInterval.compareTo(jwtVerifierMaximumStaleness) >= 0) {
      throw new IllegalArgumentException("Conversation security configuration is invalid");
    }
  }
}
