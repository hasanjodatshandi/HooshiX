package com.sajtech.notification.infrastructure.persistence;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.out.DeliveryAttemptRepository;
import com.sajtech.notification.domain.notification.model.*;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;

@SuppressWarnings("EI_EXPOSE_REP2")
public final class JooqDeliveryAttemptRepository implements DeliveryAttemptRepository {
  private final DSLContext dsl;

  public JooqDeliveryAttemptRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public List<DeliveryAttemptClaim> claimDue(int batchSize, Duration lease) {
    if (batchSize <= 0 || batchSize > 25 || lease == null || lease.isNegative() || lease.isZero()) {
      throw new IllegalArgumentException("Delivery claim configuration is invalid");
    }
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          var rows =
              tx.fetch(
                  """
          SELECT a.attempt_id,a.attempt_number,n.notification_id,n.caller_service,n.request_id,
                 n.channel,n.semantic_type,n.template_version_id,n.effective_delivery_deadline,
                 n.escrow_format_version,n.escrow_key_id,n.recipient_nonce,n.recipient_ciphertext,
                 n.subject_nonce,n.subject_ciphertext,n.text_nonce,n.text_ciphertext,n.html_nonce,
                 n.html_ciphertext,clock_timestamp() AS db_now
          FROM notification_attempt a JOIN notification n ON n.notification_id=a.notification_id
          WHERE a.state='PENDING' AND a.next_action_at<=clock_timestamp()
            AND n.lifecycle IN ('ACCEPTED','RETRY_WAIT')
          ORDER BY a.next_action_at,a.attempt_id LIMIT ? FOR UPDATE OF a,n SKIP LOCKED
          """,
                  batchSize);
          List<DeliveryAttemptClaim> result = new ArrayList<>();
          for (Record row : rows) {
            UUID attemptId = row.get("attempt_id", UUID.class);
            UUID notificationId = row.get("notification_id", UUID.class);
            Instant now = row.get("db_now", OffsetDateTime.class).toInstant();
            Instant deadline =
                row.get("effective_delivery_deadline", OffsetDateTime.class).toInstant();
            if (!now.isBefore(deadline)) {
              tx.execute(
                  "UPDATE notification_attempt SET state='COMPLETED',claimed_until=NULL,updated_at=clock_timestamp() WHERE attempt_id=?",
                  attemptId);
              JooqNotificationTerminalMutation.terminalize(
                  tx, notificationId, NotificationLifecycle.EXPIRED, false);
              continue;
            }
            UUID executionId = UUID.randomUUID();
            int changed =
                tx.execute(
                    """
            UPDATE notification_attempt SET state='DISPATCHING',execution_id=?,
              dispatched_at=clock_timestamp(),claimed_until=clock_timestamp()+(?*interval '1 millisecond'),
              updated_at=clock_timestamp() WHERE attempt_id=? AND state='PENDING'
            """,
                    executionId,
                    lease.toMillis(),
                    attemptId);
            int lifecycle =
                tx.execute(
                    "UPDATE notification SET lifecycle='DISPATCHING',updated_at=clock_timestamp() WHERE notification_id=? AND lifecycle IN ('ACCEPTED','RETRY_WAIT')",
                    notificationId);
            if (changed != 1 || lifecycle != 1)
              throw new IllegalStateException("Delivery claim transition failed");
            NotificationChannel channel =
                NotificationChannel.valueOf(row.get("channel", String.class));
            DeliveryEscrowEnvelope envelope =
                new DeliveryEscrowEnvelope(
                    notificationId,
                    row.get("caller_service", String.class),
                    row.get("request_id", UUID.class),
                    channel,
                    NotificationSemanticType.valueOf(row.get("semantic_type", String.class)),
                    row.get("template_version_id", UUID.class),
                    row.get("escrow_format_version", Integer.class),
                    row.get("escrow_key_id", String.class),
                    cipher(row, "recipient", true),
                    cipher(row, "subject", false),
                    cipher(row, "text", true),
                    cipher(row, "html", false));
            result.add(
                new DeliveryAttemptClaim(
                    notificationId,
                    attemptId,
                    executionId,
                    row.get("attempt_number", Integer.class),
                    channel,
                    deadline,
                    envelope));
          }
          return List.copyOf(result);
        });
  }

  @Override
  public void recordProviderAccepted(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration delay) {
    outcome(
        claim,
        outcome,
        (tx, attempt) -> {
          evidence(tx, claim, outcome);
          updateAttempt(tx, claim, "RECONCILING", outcome.classification(), delay, true);
          if (tx.execute(
                  "UPDATE notification SET lifecycle='PROVIDER_ACCEPTED',updated_at=clock_timestamp() WHERE notification_id=? AND lifecycle='DISPATCHING'",
                  claim.notificationId())
              != 1) throw new IllegalStateException("Provider acceptance transition failed");
        });
  }

  @Override
  public void recordTransientRetry(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration delay) {
    outcome(
        claim,
        outcome,
        (tx, attempt) -> {
          evidence(tx, claim, outcome);
          complete(tx, claim, outcome.classification());
          if (tx.execute(
                  "UPDATE notification SET lifecycle='RETRY_WAIT',updated_at=clock_timestamp() WHERE notification_id=? AND lifecycle='DISPATCHING'",
                  claim.notificationId())
              != 1) throw new IllegalStateException("Retry lifecycle transition failed");
          int number = attempt + 1;
          if (number > 4) throw new IllegalStateException("Provider attempt budget exhausted");
          tx.execute(
              "INSERT INTO notification_attempt(attempt_id,notification_id,attempt_number,state,next_action_at) VALUES (?,?,?,'PENDING',clock_timestamp()+(?*interval '1 millisecond'))",
              UUID.randomUUID(),
              claim.notificationId(),
              number,
              delay.toMillis());
        });
  }

  @Override
  public void recordAmbiguous(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration delay) {
    outcome(
        claim,
        outcome,
        (tx, attempt) -> {
          evidence(tx, claim, outcome);
          updateAttempt(tx, claim, "RECONCILING", outcome.classification(), delay, true);
        });
  }

  @Override
  public void recordPermanentFailure(DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome) {
    outcome(
        claim,
        outcome,
        (tx, attempt) -> {
          evidence(tx, claim, outcome);
          complete(tx, claim, outcome.classification());
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.FAILED_PERMANENT, false);
        });
  }

  @Override
  public void recordExpired(DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome) {
    outcome(
        claim,
        outcome,
        (tx, attempt) -> {
          evidence(tx, claim, outcome);
          complete(tx, claim, outcome.classification());
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.EXPIRED, false);
        });
  }

  @Override
  public void recordLocalPermanentFailure(DeliveryAttemptClaim claim) {
    if (claim == null) throw new IllegalArgumentException("Delivery claim is required");
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          Record row =
              tx.fetchOne(
                  "SELECT 1 FROM notification_attempt a JOIN notification n ON n.notification_id=a.notification_id WHERE a.attempt_id=? AND a.notification_id=? AND a.execution_id=? AND a.state='DISPATCHING' AND n.lifecycle='DISPATCHING' FOR UPDATE OF a,n",
                  claim.attemptId(),
                  claim.notificationId(),
                  claim.executionId());
          if (row == null)
            throw new IllegalStateException("Delivery execution is no longer active");
          if (tx.execute(
                  "UPDATE notification_attempt SET state='COMPLETED',claimed_until=NULL,updated_at=clock_timestamp() WHERE attempt_id=? AND execution_id=? AND state='DISPATCHING'",
                  claim.attemptId(),
                  claim.executionId())
              != 1) throw new IllegalStateException("Local delivery failure completion failed");
          JooqNotificationTerminalMutation.terminalize(
              tx, claim.notificationId(), NotificationLifecycle.FAILED_PERMANENT, false);
        });
  }

  private void outcome(
      DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, OutcomeMutation mutation) {
    if (claim == null || outcome == null || !outcome.liveProviderOutcome()) {
      throw new IllegalArgumentException("Canonical provider outcome is required");
    }
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          timeouts(tx);
          Record row =
              tx.fetchOne(
                  "SELECT a.attempt_number FROM notification_attempt a JOIN notification n ON n.notification_id=a.notification_id WHERE a.attempt_id=? AND a.notification_id=? AND a.execution_id=? AND a.state='DISPATCHING' AND n.lifecycle='DISPATCHING' FOR UPDATE OF a,n",
                  claim.attemptId(),
                  claim.notificationId(),
                  claim.executionId());
          if (row == null)
            throw new IllegalStateException("Delivery execution is no longer active");
          mutation.apply(tx, row.get("attempt_number", Integer.class));
        });
  }

  private static void updateAttempt(
      DSLContext tx,
      DeliveryAttemptClaim claim,
      String state,
      ProviderAttemptClassification classification,
      Duration delay,
      boolean observationStart) {
    int changed =
        tx.execute(
            """
        UPDATE notification_attempt SET state=?,classification=?,next_action_at=clock_timestamp()+(?*interval '1 millisecond'),
          claimed_until=NULL,observation_started_at=CASE WHEN ? THEN COALESCE(observation_started_at,clock_timestamp()) ELSE observation_started_at END,
          updated_at=clock_timestamp() WHERE attempt_id=? AND execution_id=? AND state='DISPATCHING'
        """,
            state,
            classification.name(),
            delay.toMillis(),
            observationStart,
            claim.attemptId(),
            claim.executionId());
    if (changed != 1) throw new IllegalStateException("Delivery attempt transition failed");
  }

  private static void complete(
      DSLContext tx, DeliveryAttemptClaim claim, ProviderAttemptClassification classification) {
    if (tx.execute(
            "UPDATE notification_attempt SET state='COMPLETED',classification=?,claimed_until=NULL,updated_at=clock_timestamp() WHERE attempt_id=? AND execution_id=? AND state='DISPATCHING'",
            classification.name(),
            claim.attemptId(),
            claim.executionId())
        != 1) throw new IllegalStateException("Delivery attempt completion failed");
  }

  private static void evidence(
      DSLContext tx, DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome) {
    tx.execute(
        "INSERT INTO provider_receipt_evidence(receipt_id,notification_id,attempt_id,provider_code,provider_correlation_id,observed_at,expires_at) VALUES (?,?,?,?,?,clock_timestamp(),clock_timestamp()+interval '30 days')",
        UUID.randomUUID(),
        claim.notificationId(),
        claim.attemptId(),
        outcome.providerCode(),
        outcome.providerCorrelationId());
  }

  private static DeliveryCiphertext cipher(Record row, String prefix, boolean required) {
    byte[] nonce = row.get(prefix + "_nonce", byte[].class);
    byte[] ciphertext = row.get(prefix + "_ciphertext", byte[].class);
    if (nonce == null || ciphertext == null) {
      if (required) throw new IllegalStateException("Required delivery ciphertext is unavailable");
      return null;
    }
    return new DeliveryCiphertext(nonce, ciphertext);
  }

  private static void timeouts(DSLContext tx) {
    tx.execute("SET LOCAL lock_timeout = '100ms'");
    tx.execute("SET LOCAL statement_timeout = '500ms'");
  }

  @FunctionalInterface
  private interface OutcomeMutation {
    void apply(DSLContext tx, int attemptNumber);
  }
}
