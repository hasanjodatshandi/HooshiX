package com.sajtech.conversation.configuration;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "conversation.model-worker")
public record ModelWorkerProperties(
    Path providerApiKeyPath,
    Duration pollInterval,
    Duration leaseDuration,
    Duration circuitOpenDuration,
    int maximumConcurrentCalls,
    int maximumConcurrentPerTenant,
    int expiryBatchSize,
    int circuitFailureThreshold) {
  public ModelWorkerProperties {
    if (providerApiKeyPath == null
        || pollInterval == null
        || pollInterval.isZero()
        || pollInterval.isNegative()
        || leaseDuration == null
        || leaseDuration.compareTo(Duration.ofSeconds(60)) <= 0
        || circuitOpenDuration == null
        || circuitOpenDuration.isZero()
        || circuitOpenDuration.isNegative()
        || maximumConcurrentCalls < 1
        || maximumConcurrentCalls > 32
        || maximumConcurrentPerTenant < 1
        || maximumConcurrentPerTenant > maximumConcurrentCalls
        || expiryBatchSize < 1
        || expiryBatchSize > 100
        || circuitFailureThreshold < 1
        || circuitFailureThreshold > 100) {
      throw new IllegalArgumentException("Conversation model worker configuration is invalid");
    }
  }
}
