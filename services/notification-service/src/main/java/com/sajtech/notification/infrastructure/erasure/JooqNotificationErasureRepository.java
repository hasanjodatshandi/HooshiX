package com.sajtech.notification.infrastructure.erasure;

import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqNotificationErasureRepository {
  private static final String ACTIONS =
      "delivery_attempt,encrypted_content,provider_receipt,recipient";
  private final DSLContext dsl;

  public JooqNotificationErasureRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void receive(ErasureCommandEvent event, Instant now) {
    dsl.execute(
        """
        INSERT INTO notification_erasure_inbox(
          event_id,erasure_request_id,participant_policy_version,state,attempt_count,
          next_attempt_at,received_at,retain_until)
        VALUES (?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        ON CONFLICT(erasure_request_id) DO UPDATE SET
          state=CASE WHEN notification_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                     THEN 'PENDING' ELSE notification_erasure_inbox.state END,
          attempt_count=CASE WHEN notification_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                             THEN 0 ELSE notification_erasure_inbox.attempt_count END,
          next_attempt_at=CASE WHEN notification_erasure_inbox.state IN ('PENDING','PROCESSING','EXHAUSTED')
                               THEN EXCLUDED.next_attempt_at ELSE notification_erasure_inbox.next_attempt_at END,
          lease_until=CASE WHEN notification_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                           THEN NULL ELSE notification_erasure_inbox.lease_until END,
          last_error_class=CASE WHEN notification_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                THEN NULL ELSE notification_erasure_inbox.last_error_class END,
          retain_until=GREATEST(notification_erasure_inbox.retain_until,EXCLUDED.retain_until),
          redrive_requested=(notification_erasure_inbox.state='PROCESSING')
        WHERE notification_erasure_inbox.event_id<>EXCLUDED.event_id
          AND notification_erasure_inbox.state<>'COMPLETED'
        """,
        UUID.fromString(event.getEventId()),
        UUID.fromString(event.getErasureRequestId()),
        event.getParticipantPolicyVersion(),
        ts(now),
        ts(now),
        ts(now.plus(Duration.ofDays(35))));
  }

  public Optional<InboxItem> claim(Instant now, Duration lease) {
    return dsl.fetchOptional(
            """
            SELECT event_id,erasure_request_id,participant_policy_version,attempt_count
            FROM notification_erasure_inbox
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
              UUID eventId = row.get("event_id", UUID.class);
              dsl.execute(
                  "UPDATE notification_erasure_inbox SET state='PROCESSING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(lease)),
                  eventId);
              return new InboxItem(
                  eventId,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("attempt_count", Integer.class));
            });
  }

  public void erasePage(List<UUID> notificationIds) {
    if (notificationIds.isEmpty()) return;
    UUID[] ids = notificationIds.toArray(UUID[]::new);
    Record activeRow =
        dsl.fetchOne(
            """
            SELECT count(*)::integer AS active
            FROM notification_attempt
            WHERE notification_id=ANY(CAST(? AS uuid[]))
              AND state IN ('DISPATCHING','RECONCILING')
            """,
            (Object) ids);
    Integer active = activeRow == null ? null : activeRow.get("active", Integer.class);
    if (active == null || active > 0) {
      throw new IllegalStateException("Notification delivery is still in flight");
    }
    dsl.execute(
        "DELETE FROM provider_receipt_evidence WHERE notification_id=ANY(CAST(? AS uuid[]))",
        (Object) ids);
    dsl.execute(
        "DELETE FROM notification_result_outbox WHERE notification_id=ANY(CAST(? AS uuid[]))",
        (Object) ids);
    dsl.execute(
        "DELETE FROM notification_attempt WHERE notification_id=ANY(CAST(? AS uuid[]))",
        (Object) ids);
    dsl.execute(
        "DELETE FROM notification WHERE notification_id=ANY(CAST(? AS uuid[]))", (Object) ids);
  }

  public void complete(InboxItem item, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE notification_erasure_inbox
            SET state='COMPLETED',lease_until=NULL,redrive_requested=FALSE,last_error_class=NULL,
                completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE event_id=? AND state='PROCESSING'
            """,
            ts(now),
            item.eventId());
    if (changed != 1) throw new IllegalStateException("Notification erasure completion failed");
    dsl.execute(
        """
        INSERT INTO notification_erasure_evidence(
          evidence_id,erasure_request_id,policy_version,event_code,action_categories,
          occurred_at,integrity_version)
        VALUES (?,?,?,'ERASURE_COMPLETED',?,CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')
        """,
        UUID.randomUUID(),
        item.erasureRequestId(),
        item.participantPolicyVersion(),
        ACTIONS,
        ts(now));
    dsl.execute(
        """
        INSERT INTO notification_erasure_receipt_outbox(
          event_id,erasure_request_id,participant_policy_version,outcome,action_categories,
          state,attempt_count,next_attempt_at,occurred_at,retain_until,updated_at)
        VALUES (?,?,?,'COMPLETED',?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        UUID.randomUUID(),
        item.erasureRequestId(),
        item.participantPolicyVersion(),
        ACTIONS,
        ts(now),
        ts(now),
        ts(now.plus(Duration.ofDays(35))),
        ts(now));
  }

  public void reschedule(UUID eventId, int attempt, Instant next, String error) {
    dsl.execute(
        "UPDATE notification_erasure_inbox SET state='PENDING',attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,next_attempt_at=CASE WHEN redrive_requested THEN next_attempt_at ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
        attempt,
        ts(next),
        error,
        eventId);
  }

  public void exhaust(UUID eventId, int attempt, String error) {
    dsl.execute(
        "UPDATE notification_erasure_inbox SET state=CASE WHEN redrive_requested THEN 'PENDING' ELSE 'EXHAUSTED' END,attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
        attempt,
        error,
        eventId);
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  public record InboxItem(
      UUID eventId, UUID erasureRequestId, String participantPolicyVersion, int attemptCount) {}
}
