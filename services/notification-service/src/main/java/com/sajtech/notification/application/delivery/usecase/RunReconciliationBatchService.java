package com.sajtech.notification.application.delivery.usecase;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.in.RunReconciliationBatch;
import com.sajtech.notification.application.delivery.port.out.*;
import com.sajtech.notification.application.delivery.service.*;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import java.time.*;
import java.util.*;

public final class RunReconciliationBatchService implements RunReconciliationBatch {
  private static final int MAX_BATCH = 25;
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
  private final DeliveryReconciliationRepository repository;
  private final DeliveryDatabaseTimePort databaseTime;
  private final ProviderAttemptPlanner planner;
  private final ProviderObservationPolicy observationPolicy;
  private final Map<NotificationChannel, NotificationProviderGateway> providers;

  public RunReconciliationBatchService(
      DeliveryReconciliationRepository repository,
      DeliveryDatabaseTimePort databaseTime,
      ProviderAttemptPlanner planner,
      ProviderObservationPolicy observationPolicy,
      List<NotificationProviderGateway> providers) {
    this.repository = repository;
    this.databaseTime = databaseTime;
    this.planner = planner;
    this.observationPolicy = observationPolicy;
    this.providers = RunDeliveryBatchService.requireLiveProviders(providers);
  }

  @Override
  public ReconciliationBatchResult run(int batchSize) {
    if (batchSize <= 0 || batchSize > MAX_BATCH) {
      throw new IllegalArgumentException("Reconciliation batch size must be between 1 and 25");
    }
    int recovered = repository.recoverStaleDispatches(batchSize);
    List<DeliveryReconciliationClaim> claims = repository.claimDue(batchSize, CLAIM_LEASE);
    int processed = 0;
    for (DeliveryReconciliationClaim claim : claims) {
      process(claim);
      processed++;
    }
    return new ReconciliationBatchResult(recovered, claims.size(), processed);
  }

  private void process(DeliveryReconciliationClaim claim) {
    Instant before = databaseTime.now();
    if (planner.observationWindowClosed(claim.channel(), claim.observationStartedAt(), before)) {
      repository.recordStatusUnknown(claim, null);
      return;
    }
    ProviderReconciliationOutcome outcome;
    try {
      outcome =
          providers
              .get(claim.channel())
              .reconcile(
                  new ProviderReconciliationRequest(
                      claim.notificationId(),
                      claim.attemptId(),
                      claim.executionId(),
                      claim.channel(),
                      claim.providerCorrelationId()));
      if (outcome == null || !outcome.liveProviderOutcome()) {
        throw new IllegalStateException("Live reconciliation returned simulated outcome");
      }
    } catch (RuntimeException ignored) {
      outcome =
          ProviderReconciliationOutcome.live(
              ProviderReconciliationStatus.INCONCLUSIVE, null, claim.providerCorrelationId());
    }
    switch (outcome.status()) {
      case DELIVERED -> repository.recordDelivered(claim, outcome);
      case PERMANENT_FAILURE -> repository.recordPermanentFailure(claim, outcome);
      case PENDING, INCONCLUSIVE -> rescheduleOrClose(claim, outcome);
    }
  }

  private void rescheduleOrClose(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
    Instant now = databaseTime.now();
    if (planner.observationWindowClosed(claim.channel(), claim.observationStartedAt(), now)) {
      repository.recordStatusUnknown(claim, outcome);
      return;
    }
    Duration elapsed = Duration.between(claim.observationStartedAt(), now);
    if (elapsed.isNegative()) elapsed = Duration.ZERO;
    repository.reschedule(claim, outcome, observationPolicy.nextDelay(claim.channel(), elapsed));
  }
}
