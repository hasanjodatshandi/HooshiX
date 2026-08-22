package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.out.DeliveryReconciliationRepository;
import com.sajtech.notification.domain.notification.model.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

@SuppressWarnings("EI_EXPOSE_REP2")
public final class JooqDeliveryReconciliationRepository
    implements DeliveryReconciliationRepository {
  private final DSLContext dsl;

  public JooqDeliveryReconciliationRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public int recoverStaleDispatches(int batchSize) {
    if (batchSize <= 0 || batchSize > 25)
      throw new IllegalArgumentException("Recovery batch is invalid");
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          var rows =
              tx.fetch(
                  """
          SELECT a.attempt_id FROM notification_attempt a
          JOIN notification n ON n.notification_id=a.notification_id
          WHERE a.state='DISPATCHING' AND a.claimed_until<=clock_timestamp()
            AND n.lifecycle='DISPATCHING'
          ORDER BY a.claimed_until,a.attempt_id LIMIT ? FOR UPDATE OF a SKIP LOCKED
          """,
                  batchSize);
          for (Record row : rows)
            tx.execute(
                """
          UPDATE notification_attempt SET state='RECONCILING',classification='AMBIGUOUS',
            next_action_at=clock_timestamp(),claimed_until=NULL,
            observation_started_at=COALESCE(observation_started_at,dispatched_at,clock_timestamp()),
            updated_at=clock_timestamp() WHERE attempt_id=? AND state='DISPATCHING'
          """,
                row.get("attempt_id", UUID.class));
          return rows.size();
        });
  }

  @Override
  public List<DeliveryReconciliationClaim> claimDue(int batchSize, Duration lease) {
    if (batchSize <= 0 || batchSize > 25 || lease == null || lease.isZero() || lease.isNegative())
      throw new IllegalArgumentException("Reconciliation claim configuration is invalid");
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          var rows =
              tx.fetch(
                  """
          SELECT a.attempt_id,a.notification_id,a.execution_id,n.channel,n.lifecycle,
                 COALESCE(a.observation_started_at,a.dispatched_at) AS observation_started_at,
                 e.provider_correlation_id
          FROM notification_attempt a JOIN notification n ON n.notification_id=a.notification_id
          LEFT JOIN LATERAL (
            SELECT provider_correlation_id FROM provider_receipt_evidence p
            WHERE p.attempt_id=a.attempt_id ORDER BY observed_at DESC,receipt_id DESC LIMIT 1
          ) e ON true
          WHERE a.state='RECONCILING' AND a.next_action_at<=clock_timestamp()
            AND (a.claimed_until IS NULL OR a.claimed_until<=clock_timestamp())
            AND n.lifecycle IN ('DISPATCHING','PROVIDER_ACCEPTED')
          ORDER BY a.next_action_at,a.attempt_id LIMIT ? FOR UPDATE OF a SKIP LOCKED
          """,
                  batchSize);
          List<DeliveryReconciliationClaim> result = new ArrayList<>();
          for (Record row : rows) {
            UUID attemptId = row.get("attempt_id", UUID.class);
            if (tx.execute(
                    "UPDATE notification_attempt SET claimed_until=clock_timestamp()+(?*interval '1 millisecond'),updated_at=clock_timestamp() WHERE attempt_id=? AND state='RECONCILING'",
                    lease.toMillis(),
                    attemptId)
                != 1) throw new IllegalStateException("Reconciliation claim transition failed");
            OffsetDateTime observed = row.get("observation_started_at", OffsetDateTime.class);
            if (observed == null)
              throw new IllegalStateException("Reconciliation start time is unavailable");
            result.add(
                new DeliveryReconciliationClaim(
                    row.get("notification_id", UUID.class),
                    attemptId,
                    row.get("execution_id", UUID.class),
                    NotificationChannel.valueOf(row.get("channel", String.class)),
                    NotificationLifecycle.valueOf(row.get("lifecycle", String.class)),
                    observed.toInstant(),
                    row.get("provider_correlation_id", String.class)));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public void recordDelivered(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
    mutate(
        claim,
        outcome,
        (tx, lifecycle) -> {
          if (lifecycle == NotificationLifecycle.DISPATCHING
              && tx.execute(
                      "UPDATE notification SET lifecycle='PROVIDER_ACCEPTED',updated_at=clock_timestamp() WHERE notification_id=? AND lifecycle='DISPATCHING'",
                      claim.notificationId())
                  != 1)
            throw new IllegalStateException("Provider acceptance reconciliation failed");
          complete(tx, claim);
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.DELIVERED, true);
        });
  }

  @Override
  public void recordPermanentFailure(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
    mutate(
        claim,
        outcome,
        (tx, lifecycle) -> {
          complete(tx, claim);
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.FAILED_PERMANENT, false);
        });
  }

  @Override
  public void reschedule(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome, Duration delay) {
    mutate(
        claim,
        outcome,
        (tx, lifecycle) -> {
          if (tx.execute(
                  "UPDATE notification_attempt SET next_action_at=clock_timestamp()+(?*interval '1 millisecond'),claimed_until=NULL,updated_at=clock_timestamp() WHERE attempt_id=? AND execution_id=? AND state='RECONCILING'",
                  delay.toMillis(),
                  claim.attemptId(),
                  claim.executionId())
              != 1) throw new IllegalStateException("Reconciliation reschedule failed");
        });
  }

  @Override
  public void recordStatusUnknown(
      DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
    mutate(
        claim,
        outcome,
        (tx, lifecycle) -> {
          complete(tx, claim);
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.DELIVERY_STATUS_UNKNOWN, false);
        });
  }

  private void mutate(
      DeliveryReconciliationClaim claim,
      ProviderReconciliationOutcome outcome,
      ReconciliationMutation mutation) {
    if (claim == null) throw new IllegalArgumentException("Reconciliation claim is required");
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          Record row =
              tx.fetchOne(
                  """
          SELECT n.lifecycle FROM notification_attempt a JOIN notification n ON n.notification_id=a.notification_id
          WHERE a.attempt_id=? AND a.notification_id=? AND a.execution_id=? AND a.state='RECONCILING'
            AND n.lifecycle IN ('DISPATCHING','PROVIDER_ACCEPTED') FOR UPDATE OF a,n
          """,
                  claim.attemptId(),
                  claim.notificationId(),
                  claim.executionId());
          if (row == null)
            throw new IllegalStateException("Reconciliation execution is no longer active");
          if (outcome != null && outcome.liveProviderOutcome()) evidence(tx, claim, outcome);
          mutation.apply(tx, NotificationLifecycle.valueOf(row.get("lifecycle", String.class)));
        });
  }

  private static void complete(DSLContext tx, DeliveryReconciliationClaim claim) {
    if (tx.execute(
            "UPDATE notification_attempt SET state='COMPLETED',claimed_until=NULL,updated_at=clock_timestamp() WHERE attempt_id=? AND execution_id=? AND state='RECONCILING'",
            claim.attemptId(),
            claim.executionId())
        != 1) throw new IllegalStateException("Reconciliation completion failed");
  }

  private static void evidence(
      DSLContext tx, DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
    tx.execute(
        "INSERT INTO provider_receipt_evidence(receipt_id,notification_id,attempt_id,provider_code,provider_correlation_id,observed_at,expires_at) VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp()+interval '30 days')",
        UUID.randomUUID(),
        claim.notificationId(),
        claim.attemptId(),
        outcome.providerCode(),
        outcome.providerCorrelationId());
  }

  private static void timeouts(DSLContext tx) {
    tx.execute("SET LOCAL lock_timeout = '100ms'");
    tx.execute("SET LOCAL statement_timeout = '500ms'");
  }

  @FunctionalInterface
  private interface ReconciliationMutation {
    void apply(DSLContext tx, NotificationLifecycle lifecycle);
  }
}
