package com.sajtech.conversation.application.service;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import com.sajtech.conversation.application.port.out.ModelProvider;
import com.sajtech.conversation.application.port.out.ModelRunWorkerRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class ModelRunWorker {
  private final ModelPolicyProvider policies;
  private final ModelRunWorkerRepository runs;
  private final ModelProvider provider;
  private final Clock clock;
  private final Duration leaseDuration;
  private final int maximumConcurrentPerTenant;
  private final int expiryBatchSize;
  private final CircuitBreaker breaker;

  public ModelRunWorker(
      ModelPolicyProvider policies,
      ModelRunWorkerRepository runs,
      ModelProvider provider,
      Clock clock,
      Duration leaseDuration,
      int maximumConcurrentPerTenant,
      int expiryBatchSize,
      int circuitFailureThreshold,
      Duration circuitOpenDuration) {
    this.policies = Objects.requireNonNull(policies);
    this.runs = Objects.requireNonNull(runs);
    this.provider = Objects.requireNonNull(provider);
    this.clock = Objects.requireNonNull(clock);
    this.leaseDuration = Objects.requireNonNull(leaseDuration);
    this.maximumConcurrentPerTenant = maximumConcurrentPerTenant;
    this.expiryBatchSize = expiryBatchSize;
    this.breaker = new CircuitBreaker(circuitFailureThreshold, circuitOpenDuration);
  }

  public boolean runOnce() {
    ModelExecutionPolicy policy;
    try {
      policy = policies.requireApprovedPolicy();
    } catch (ConversationException exception) {
      if (exception.error() == ConversationError.MODEL_EXECUTION_DISABLED) return false;
      throw exception;
    }
    Instant now = clock.instant();
    runs.expireUnknown(policy, now, expiryBatchSize);
    if (!breaker.permits(now)) return false;
    return runs.claim(policy, now, leaseDuration, maximumConcurrentPerTenant)
        .map(
            claim -> {
              ModelProviderResult result =
                  provider.execute(
                      new ModelProviderRequest(claim.runId(), policy, claim.messages()));
              breaker.record(result.outcome(), clock.instant());
              runs.complete(claim, policy, result, clock.instant());
              return true;
            })
        .orElse(false);
  }

  private static final class CircuitBreaker {
    private final int failureThreshold;
    private final Duration openDuration;
    private int consecutiveFailures;
    private Instant openUntil;

    private CircuitBreaker(int failureThreshold, Duration openDuration) {
      if (failureThreshold < 1
          || openDuration == null
          || openDuration.isZero()
          || openDuration.isNegative()) {
        throw new IllegalArgumentException("Provider circuit breaker configuration is invalid");
      }
      this.failureThreshold = failureThreshold;
      this.openDuration = openDuration;
    }

    private synchronized boolean permits(Instant now) {
      if (openUntil == null) return true;
      if (now.isBefore(openUntil)) return false;
      openUntil = null;
      consecutiveFailures = 0;
      return true;
    }

    private synchronized void record(ModelProviderOutcome outcome, Instant now) {
      if (outcome == ModelProviderOutcome.SUCCEEDED
          || outcome == ModelProviderOutcome.SAFETY_REJECTED
          || outcome == ModelProviderOutcome.DEFINITIVE_REJECTION) {
        consecutiveFailures = 0;
        openUntil = null;
        return;
      }
      consecutiveFailures++;
      if (consecutiveFailures >= failureThreshold) {
        openUntil = now.plus(openDuration);
      }
    }
  }
}
