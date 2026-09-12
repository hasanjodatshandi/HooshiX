package com.sajtech.conversation.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conversation")
public record ConversationProperties(
    boolean providerRuntimeEnabled,
    Path contentKeyRingPath,
    Duration keyRingRefreshInterval,
    Duration keyRingMaximumStaleness) {
  public ConversationProperties {
    if (contentKeyRingPath == null
        || keyRingRefreshInterval == null
        || keyRingRefreshInterval.isZero()
        || keyRingRefreshInterval.isNegative()
        || keyRingMaximumStaleness == null
        || keyRingMaximumStaleness.isZero()
        || keyRingMaximumStaleness.isNegative()
        || keyRingRefreshInterval.compareTo(keyRingMaximumStaleness) >= 0) {
      throw new IllegalArgumentException("Conversation security configuration is invalid");
    }
  }
}
