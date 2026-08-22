package com.sajtech.notification.application.delivery.usecase;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.in.RunDeliveryBatch;
import com.sajtech.notification.application.delivery.port.out.*;
import com.sajtech.notification.application.delivery.service.*;
import com.sajtech.notification.domain.notification.model.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.random.RandomGenerator;

public final class RunDeliveryBatchService implements RunDeliveryBatch {
  private static final int MAX_BATCH = 25;
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
  private final DeliveryAttemptRepository repository;
  private final DeliveryEscrowReaderPort escrow;
  private final DeliveryDatabaseTimePort databaseTime;
  private final ProviderAttemptPlanner planner;
  private final ProviderObservationPolicy observationPolicy;
  private final RandomGenerator random;
  private final Map<NotificationChannel, NotificationProviderGateway> providers;

  public RunDeliveryBatchService(
      DeliveryAttemptRepository repository,
      DeliveryEscrowReaderPort escrow,
      DeliveryDatabaseTimePort databaseTime,
      ProviderAttemptPlanner planner,
      ProviderObservationPolicy observationPolicy,
      RandomGenerator random,
      List<NotificationProviderGateway> providers) {
    this.repository = repository;
    this.escrow = escrow;
    this.databaseTime = databaseTime;
    this.planner = planner;
    this.observationPolicy = observationPolicy;
    this.random = random;
    this.providers = requireLiveProviders(providers);
  }

  @Override
  public DeliveryBatchResult run(int batchSize) {
    if (batchSize <= 0 || batchSize > MAX_BATCH) {
      throw new IllegalArgumentException("Delivery batch size must be between 1 and 25");
    }
    List<DeliveryAttemptClaim> claims = repository.claimDue(batchSize, CLAIM_LEASE);
    int completed = 0;
    for (DeliveryAttemptClaim claim : claims) {
      process(claim);
      completed++;
    }
    return new DeliveryBatchResult(claims.size(), completed);
  }

  private void process(DeliveryAttemptClaim claim) {
    DecryptedDeliveryPayload payload;
    try {
      payload = escrow.decrypt(claim.escrow());
    } catch (IllegalStateException localEscrowFailure) {
      repository.recordLocalPermanentFailure(claim);
      return;
    }
    ProviderDispatchMessage message =
        new ProviderDispatchMessage(
            claim.notificationId(),
            claim.attemptId(),
            claim.executionId(),
            claim.attemptNumber(),
            claim.channel(),
            claim.effectiveDeliveryDeadline(),
            payload.recipient(),
            payload.subject(),
            payload.text(),
            payload.html());
    ProviderDispatchOutcome outcome;
    try {
      outcome = providers.get(claim.channel()).dispatch(message);
      if (outcome == null || !outcome.liveProviderOutcome()) {
        throw new IllegalStateException("Live delivery worker received simulated provider outcome");
      }
    } catch (RuntimeException ignored) {
      outcome = ProviderDispatchOutcome.live(ProviderAttemptClassification.AMBIGUOUS, null, null);
    }
    Instant now = databaseTime.now();
    ProviderAttemptDecision decision =
        planner.plan(
            claim.channel(),
            claim.attemptNumber(),
            outcome.classification(),
            now,
            claim.effectiveDeliveryDeadline(),
            random);
    switch (decision.action()) {
      case MARK_PROVIDER_ACCEPTED ->
          repository.recordProviderAccepted(
              claim, outcome, observationPolicy.nextDelay(claim.channel(), Duration.ZERO));
      case RETRY_AFTER -> repository.recordTransientRetry(claim, outcome, decision.retryDelay());
      case RECONCILE ->
          repository.recordAmbiguous(
              claim, outcome, observationPolicy.nextDelay(claim.channel(), Duration.ZERO));
      case FAIL_PERMANENT -> repository.recordPermanentFailure(claim, outcome);
      case EXPIRE -> repository.recordExpired(claim, outcome);
    }
  }

  static Map<NotificationChannel, NotificationProviderGateway> requireLiveProviders(
      List<NotificationProviderGateway> gateways) {
    EnumMap<NotificationChannel, NotificationProviderGateway> result =
        new EnumMap<>(NotificationChannel.class);
    for (NotificationProviderGateway gateway : List.copyOf(gateways)) {
      if (!gateway.liveDelivery()) continue;
      if (result.put(gateway.channel(), gateway) != null) {
        throw new IllegalStateException(
            "Multiple live notification providers configured for one channel");
      }
    }
    for (NotificationChannel channel : NotificationChannel.values()) {
      if (!result.containsKey(channel)) {
        throw new IllegalStateException(
            "Live notification provider is missing for channel " + channel);
      }
    }
    return Map.copyOf(result);
  }
}
