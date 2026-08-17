package com.sajtech.notification.application.delivery.service;

import com.sajtech.notification.application.delivery.model.ProviderAttemptAction;
import com.sajtech.notification.application.delivery.model.ProviderAttemptDecision;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

public final class ProviderAttemptPlanner {
  private final ProviderRetryPolicy retryPolicy;

  public ProviderAttemptPlanner(ProviderRetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
  }

  public ProviderAttemptDecision plan(
      NotificationChannel channel,
      int completedAttemptNumber,
      ProviderAttemptClassification classification,
      Instant databaseNow,
      Instant effectiveDeliveryDeadline,
      RandomGenerator random) {
    if (channel == null
        || classification == null
        || databaseNow == null
        || effectiveDeliveryDeadline == null
        || random == null) {
      throw new IllegalArgumentException("Provider attempt planning input is incomplete");
    }
    if (!databaseNow.isBefore(effectiveDeliveryDeadline)) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.EXPIRE);
    }
    return switch (classification) {
      case DEFINITIVE_ACCEPTED ->
          ProviderAttemptDecision.action(ProviderAttemptAction.MARK_PROVIDER_ACCEPTED);
      case DEFINITIVE_PERMANENT_FAILURE ->
          ProviderAttemptDecision.action(ProviderAttemptAction.FAIL_PERMANENT);
      case AMBIGUOUS -> ProviderAttemptDecision.action(ProviderAttemptAction.RECONCILE);
      case DEFINITIVE_TRANSIENT_FAILURE ->
          transientFailure(
              channel,
              completedAttemptNumber,
              databaseNow,
              effectiveDeliveryDeadline,
              random);
    };
  }

  public boolean observationWindowClosed(
      NotificationChannel channel, Instant providerAcceptedAt, Instant databaseNow) {
    if (channel == null || providerAcceptedAt == null || databaseNow == null) {
      throw new IllegalArgumentException("Reconciliation timing input is incomplete");
    }
    return !databaseNow.isBefore(providerAcceptedAt.plus(channel.observationWindow()));
  }

  private ProviderAttemptDecision transientFailure(
      NotificationChannel channel,
      int completedAttemptNumber,
      Instant databaseNow,
      Instant effectiveDeliveryDeadline,
      RandomGenerator random) {
    if (!retryPolicy.shouldRetry(
        channel,
        completedAttemptNumber,
        ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE)) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.FAIL_PERMANENT);
    }
    Duration delay = retryPolicy.nextDelay(channel, completedAttemptNumber, random);
    if (!databaseNow.plus(delay).isBefore(effectiveDeliveryDeadline)) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.EXPIRE);
    }
    return ProviderAttemptDecision.retryAfter(delay);
  }
}
