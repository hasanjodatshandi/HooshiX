package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.contract.v1.ErasureReceiptEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqErasureReceiptCoordinator {
  private final DSLContext dsl;

  public JooqErasureReceiptCoordinator(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void receive(ErasureReceiptEvent event, Instant now) {
    dsl.execute(
        """
        INSERT INTO identity_erasure_receipt_inbox(
          event_id,erasure_request_id,participant,outcome,participant_policy_version,
          action_categories,state,attempt_count,next_attempt_at,received_at,retain_until)
        VALUES (?,?,?,?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        ON CONFLICT(event_id) DO NOTHING
        """,
        UUID.fromString(event.getEventId()),
        UUID.fromString(event.getErasureRequestId()),
        participant(event),
        outcome(event),
        event.getParticipantPolicyVersion(),
        String.join(",", event.getActionCategoriesList()),
        ts(now),
        ts(now),
        ts(now.plus(Duration.ofDays(35))));
  }

  public Optional<InboxItem> claim(Instant now, Duration lease) {
    return dsl.fetchOptional(
            """
            SELECT event_id,erasure_request_id,participant,outcome,
                   participant_policy_version,action_categories,attempt_count
            FROM identity_erasure_receipt_inbox
            WHERE state IN ('PENDING','PROCESSING')
              AND next_attempt_at<=CAST(? AS TIMESTAMP WITH TIME ZONE)
              AND (lease_until IS NULL OR lease_until<=CAST(? AS TIMESTAMP WITH TIME ZONE))
            ORDER BY next_attempt_at,event_id
            LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
            ts(now),
            ts(now))
        .map(
            row -> {
              UUID id = row.get("event_id", UUID.class);
              dsl.execute(
                  "UPDATE identity_erasure_receipt_inbox SET state='PROCESSING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(lease)),
                  id);
              return new InboxItem(
                  id,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant", String.class),
                  row.get("outcome", String.class),
                  row.get("participant_policy_version", String.class),
                  row.get("action_categories", String.class),
                  row.get("attempt_count", Integer.class));
            });
  }

  public void apply(InboxItem item, Instant now) {
    Record request =
        dsl.fetchOne(
            "SELECT participant_policy_version,state FROM identity_erasure_request WHERE erasure_request_id=? FOR UPDATE",
            item.erasureRequestId());
    if (request == null
        || !item.participantPolicyVersion()
            .equals(request.get("participant_policy_version", String.class))) {
      throw new IllegalStateException("Erasure receipt policy is invalid");
    }
    String state =
        switch (item.outcome()) {
          case "COMPLETED" -> "COMPLETED";
          case "BLOCKED_BY_LEGAL_HOLD" -> "BLOCKED_BY_LEGAL_HOLD";
          case "FAILED_RETRYABLE" -> "FAILED_RETRYABLE";
          default -> throw new IllegalStateException("Erasure receipt outcome is invalid");
        };
    int changed =
        dsl.execute(
            """
            UPDATE identity_erasure_participant
            SET state=?,receipt_event_id=?,completed_at=CASE WHEN ?='COMPLETED'
                    THEN CAST(? AS TIMESTAMP WITH TIME ZONE) ELSE NULL END,
                updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE erasure_request_id=? AND participant=?
            """,
            state,
            item.eventId(),
            state,
            ts(now),
            ts(now),
            item.erasureRequestId(),
            item.participant());
    if (changed != 1)
      throw new IllegalStateException("Erasure participant receipt is unregistered");
    dsl.execute(
        "UPDATE identity_erasure_receipt_inbox SET state='COMPLETED',lease_until=NULL,last_error_class=NULL,completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='PROCESSING'",
        ts(now),
        item.eventId());
    Record remaining =
        dsl.fetchOne(
            "SELECT count(*)::integer AS count FROM identity_erasure_participant WHERE erasure_request_id=? AND state<>'COMPLETED'",
            item.erasureRequestId());
    if (remaining != null && Integer.valueOf(0).equals(remaining.get("count", Integer.class))) {
      dsl.execute(
          "UPDATE identity_erasure_request SET state='COMPLETED',completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state<>'COMPLETED'",
          ts(now),
          ts(now),
          item.erasureRequestId());
      dsl.execute(
          "INSERT INTO identity_erasure_evidence(evidence_id,erasure_request_id,service,policy_version,event_code,action_categories,occurred_at,integrity_version) VALUES (?,?,'identity-service',?,'GLOBAL_ERASURE_COMPLETED','required_participant_receipts',CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')",
          UUID.randomUUID(),
          item.erasureRequestId(),
          item.participantPolicyVersion(),
          ts(now));
    } else if ("FAILED_RETRYABLE".equals(state)) {
      dsl.execute(
          "UPDATE identity_erasure_request SET state='FAILED_RETRYABLE',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state<>'COMPLETED'",
          ts(now),
          item.erasureRequestId());
    } else if ("BLOCKED_BY_LEGAL_HOLD".equals(state)) {
      dsl.execute(
          "UPDATE identity_erasure_request SET state='BLOCKED_BY_LEGAL_HOLD',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state<>'COMPLETED'",
          ts(now),
          item.erasureRequestId());
    }
  }

  public void reschedule(UUID eventId, int attempt, Instant next, Throwable failure) {
    dsl.execute(
        "UPDATE identity_erasure_receipt_inbox SET state='PENDING',attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,last_error_class=? WHERE event_id=? AND state='PROCESSING'",
        attempt,
        ts(next),
        safeError(failure),
        eventId);
  }

  public void exhaust(UUID eventId, int attempt, Throwable failure) {
    dsl.execute(
        "UPDATE identity_erasure_receipt_inbox SET state='EXHAUSTED',attempt_count=?,lease_until=NULL,last_error_class=? WHERE event_id=? AND state='PROCESSING'",
        attempt,
        safeError(failure),
        eventId);
  }

  private static String participant(ErasureReceiptEvent event) {
    return event.getParticipant().name().replace("ERASURE_PARTICIPANT_", "");
  }

  private static String outcome(ErasureReceiptEvent event) {
    return event.getOutcome().name().replace("ERASURE_PARTICIPANT_OUTCOME_", "");
  }

  private static String safeError(Throwable failure) {
    String value =
        failure.getClass().getSimpleName().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    return value.substring(0, Math.min(64, value.length()));
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  public record InboxItem(
      UUID eventId,
      UUID erasureRequestId,
      String participant,
      String outcome,
      String participantPolicyVersion,
      String actionCategories,
      int attemptCount) {}
}
