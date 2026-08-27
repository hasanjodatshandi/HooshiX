package com.sajtech.webbff.infrastructure.erasure;

import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqWebBffErasureRepository {
  private static final String ACTIONS = "browser_session,csrf_secret,refresh_credential";
  private final DSLContext dsl;

  public JooqWebBffErasureRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void receive(ErasureCommandEvent event, Instant now) {
    dsl.execute(
        """
        INSERT INTO web_bff_erasure_inbox(
          event_id,erasure_request_id,participant_policy_version,state,attempt_count,
          next_attempt_at,received_at,retain_until)
        VALUES (?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        ON CONFLICT(erasure_request_id) DO UPDATE SET
          state=CASE WHEN web_bff_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                     THEN 'PENDING' ELSE web_bff_erasure_inbox.state END,
          attempt_count=CASE WHEN web_bff_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                             THEN 0 ELSE web_bff_erasure_inbox.attempt_count END,
          next_attempt_at=CASE WHEN web_bff_erasure_inbox.state IN ('PENDING','PROCESSING','EXHAUSTED')
                               THEN EXCLUDED.next_attempt_at ELSE web_bff_erasure_inbox.next_attempt_at END,
          lease_until=CASE WHEN web_bff_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                           THEN NULL ELSE web_bff_erasure_inbox.lease_until END,
          last_error_class=CASE WHEN web_bff_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                THEN NULL ELSE web_bff_erasure_inbox.last_error_class END,
          retain_until=GREATEST(web_bff_erasure_inbox.retain_until,EXCLUDED.retain_until),
          redrive_requested=(web_bff_erasure_inbox.state='PROCESSING')
        WHERE web_bff_erasure_inbox.event_id<>EXCLUDED.event_id
          AND web_bff_erasure_inbox.state<>'COMPLETED'
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
            FROM web_bff_erasure_inbox
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
                  "UPDATE web_bff_erasure_inbox SET state='PROCESSING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(lease)),
                  eventId);
              return new InboxItem(
                  eventId,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("attempt_count", Integer.class));
            });
  }

  public void complete(InboxItem item, Instant now) {
    int changed =
        dsl.execute(
            "UPDATE web_bff_erasure_inbox SET state='COMPLETED',lease_until=NULL,redrive_requested=FALSE,last_error_class=NULL,completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='PROCESSING'",
            ts(now),
            item.eventId());
    if (changed != 1) throw new IllegalStateException("BFF erasure completion failed");
    dsl.execute(
        "INSERT INTO web_bff_erasure_evidence(evidence_id,erasure_request_id,policy_version,event_code,action_categories,occurred_at,integrity_version) VALUES (?,?,?,'ERASURE_COMPLETED',?,CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')",
        UUID.randomUUID(),
        item.erasureRequestId(),
        item.participantPolicyVersion(),
        ACTIONS,
        ts(now));
    dsl.execute(
        """
        INSERT INTO web_bff_erasure_receipt_outbox(
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
        "UPDATE web_bff_erasure_inbox SET state='PENDING',attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,next_attempt_at=CASE WHEN redrive_requested THEN next_attempt_at ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
        attempt,
        ts(next),
        error,
        eventId);
  }

  public void exhaust(UUID eventId, int attempt, String error) {
    dsl.execute(
        "UPDATE web_bff_erasure_inbox SET state=CASE WHEN redrive_requested THEN 'PENDING' ELSE 'EXHAUSTED' END,attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
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
