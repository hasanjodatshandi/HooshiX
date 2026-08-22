package com.sajtech.notification.application.delivery.usecase;

import com.sajtech.notification.application.delivery.model.ProviderAttemptAction;
import com.sajtech.notification.application.delivery.model.ProviderAttemptDecision;
import com.sajtech.notification.application.delivery.model.ProviderDispatchMessage;
import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.port.out.DeliveryExecutionRepository;
import com.sajtech.notification.application.delivery.service.ProviderAttemptPlanner;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

public final class ApplyProviderOutcomeService {
  private final DeliveryExecutionRepository repository;
  private final ProviderAttemptPlanner planner;

  public ApplyProviderOutcomeService(DeliveryExecutionRepository repository, ProviderAttemptPlanner planner) {
    this.repository = repository;
    this.planner = planner;
  }

  public void apply(ProviderDispatchMessage message, ProviderDispatchOutcome outcome, int attemptNumber, Instant now, Instant deadline) {
    repository.recordOutcome(message, outcome);
    if (!outcome.liveProviderOutcome()) {
      return;
    }
    ProviderAttemptDecision decision = planner.plan(
        message.channel(), attemptNumber, outcome.classification(), now, deadline, RandomGenerator.getDefault());
    switch (decision.action()) {
      case RETRY_AFTER -> repository.scheduleRetry(message, decision.retryDelay());
      case FAIL_PERMANENT -> repository.markPermanentFailure(message);
      case MARK_PROVIDER_ACCEPTED, RECONCILE, EXPIRE -> repository.recordOutcome(message, outcome);
    }
  }
}
