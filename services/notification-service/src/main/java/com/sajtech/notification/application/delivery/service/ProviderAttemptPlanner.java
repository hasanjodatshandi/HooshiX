package com.sajtech.notification.application.delivery.service;

import com.sajtech.notification.application.delivery.model.ProviderAttemptAction;
import com.sajtech.notification.application.delivery.model.ProviderAttemptDecision;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.ProviderOutcomeClassification;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.Instant;
import java.util.random.RandomGenerator;

public final class ProviderAttemptPlanner {
  private final ProviderRetryPolicy retryPolicy;

  public ProviderAttemptPlanner(ProviderRetryPolicy retryPolicy) {
    this.retryPolicy = retryPolicy;
  }

  public ProviderAttemptDecision afterOutcome(
      NotificationChannel channel,
      int completedAttemptNumber,
      ProviderOutcomeClassification classification,
      Instant databaseNow,
      Instant effectiveDeliveryDeadline,
      RandomGenerator random) {
    if (channel == null
        || classification == null
        || databaseNow == null
        || effectiveDeliveryDeadline == null
        || random == null) {
      throw new IllegalArgumentException("Provider attempt planning input is required");
    }
    if (completedAttemptNumber <= 0) {
      throw new IllegalArgumentException("Completed attempt number must be positive");
    }
    if (!databaseNow.isBefore(effectiveDeliveryDeadline)) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.EXPIRE);
    }

    return switch (classification) {
      case ACCEPTED -> ProviderAttemptDecision.action(ProviderAttemptAction.ACCEPT_PROVIDER_RESULT);
      case DEFINITIVE_PERMANENT_FAILURE ->
          ProviderAttemptDecision.action(ProviderAttemptAction.FAIL_PERMANENTLY);
      case AMBIGUOUS -> ProviderAttemptDecision.action(ProviderAttemptAction.RECONCILE);
      case DEFINITIVE_TRANSIENT_FAILURE ->
          transientFailure(
              channel, completedAttemptNumber, databaseNow, effectiveDeliveryDeadline, random);
    };
  }

  private ProviderAttemptDecision transientFailure(
      NotificationChannel channel,
      int completedAttemptNumber,
      Instant databaseNow,
      Instant effectiveDeliveryDeadline,
      RandomGenerator random) {
    var delay = retryPolicy.nextDelay(channel, completedAttemptNumber, random);
    if (delay.isEmpty()) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.FAIL_PERMANENTLY);
    }
    Instant retryAt = databaseNow.plus(delay.get());
    if (!retryAt.isBefore(effectiveDeliveryDeadline)) {
      return ProviderAttemptDecision.action(ProviderAttemptAction.EXPIRE);
    }
    return ProviderAttemptDecision.retryAt(retryAt);
  }
}
