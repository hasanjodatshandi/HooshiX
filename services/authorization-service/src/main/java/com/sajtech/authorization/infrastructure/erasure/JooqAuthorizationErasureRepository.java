package com.sajtech.authorization.infrastructure.erasure;

import com.sajtech.identity.contract.v1.ErasureCommandEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqAuthorizationErasureRepository {
  private static final String ACTIONS =
      "authorization_assignment,membership_projection,security_actor_reference";
  private final DSLContext dsl;

  public JooqAuthorizationErasureRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public void receive(ErasureCommandEvent event, Instant now) {
    dsl.execute(
        """
        INSERT INTO authorization_erasure_inbox(
          event_id,erasure_request_id,participant_policy_version,state,attempt_count,
          next_attempt_at,received_at,retain_until)
        VALUES (?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        ON CONFLICT(erasure_request_id) DO UPDATE SET
          state=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                     THEN 'PENDING' ELSE authorization_erasure_inbox.state END,
          attempt_count=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                             THEN 0 ELSE authorization_erasure_inbox.attempt_count END,
          next_attempt_at=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','PROCESSING','EXHAUSTED')
                               THEN EXCLUDED.next_attempt_at ELSE authorization_erasure_inbox.next_attempt_at END,
          lease_until=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                           THEN NULL ELSE authorization_erasure_inbox.lease_until END,
          last_error_class=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                THEN NULL ELSE authorization_erasure_inbox.last_error_class END,
          target_user_id=CASE WHEN authorization_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                              THEN NULL ELSE authorization_erasure_inbox.target_user_id END,
          retain_until=GREATEST(authorization_erasure_inbox.retain_until,EXCLUDED.retain_until),
          redrive_requested=(authorization_erasure_inbox.state='PROCESSING')
        WHERE authorization_erasure_inbox.event_id<>EXCLUDED.event_id
          AND authorization_erasure_inbox.state<>'COMPLETED'
        """,
        UUID.fromString(event.getEventId()),
        UUID.fromString(event.getErasureRequestId()),
        event.getParticipantPolicyVersion(),
        ts(now),
        ts(now),
        ts(now.plus(java.time.Duration.ofDays(35))));
  }

  public Optional<InboxItem> claim(Instant now, java.time.Duration lease) {
    return dsl.fetchOptional(
            """
            SELECT event_id,erasure_request_id,participant_policy_version,attempt_count
            FROM authorization_erasure_inbox
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
                  "UPDATE authorization_erasure_inbox SET state='PROCESSING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(lease)),
                  eventId);
              return new InboxItem(
                  eventId,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("attempt_count", Integer.class));
            });
  }

  public boolean apply(ErasureCommandEvent event, UUID userId, Instant now) {
    UUID eventId = UUID.fromString(event.getEventId());
    int owned =
        dsl.execute(
            "UPDATE authorization_erasure_inbox SET target_user_id=? WHERE event_id=? AND state='PROCESSING'",
            userId,
            eventId);
    if (owned != 1) return false;

    List<MembershipTarget> targets =
        dsl.fetch(
                """
                SELECT tenant_id,membership_id
                FROM authorization_user_tenant_erasure_index
                WHERE user_id=?
                ORDER BY tenant_id,membership_id
                FOR UPDATE
                """,
                userId)
            .map(
                row ->
                    new MembershipTarget(
                        row.get("tenant_id", UUID.class), row.get("membership_id", UUID.class)));
    try {
      for (MembershipTarget target : targets) {
        setTenant(target.tenantId());
        dsl.execute(
            "DELETE FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=?",
            target.tenantId(),
            target.membershipId());
        dsl.execute(
            "DELETE FROM authorization_membership_role WHERE tenant_id=? AND membership_id=?",
            target.tenantId(),
            target.membershipId());
        dsl.execute(
            "DELETE FROM authorization_membership_removal_reservation WHERE tenant_id=? AND membership_id=?",
            target.tenantId(),
            target.membershipId());
        dsl.execute(
            "DELETE FROM authorization_membership_projection WHERE tenant_id=? AND membership_id=? AND user_id=?",
            target.tenantId(),
            target.membershipId(),
            userId);
      }
    } finally {
      dsl.fetchValue("SELECT set_config('app.current_tenant_id','',true)");
    }
    dsl.execute("DELETE FROM authorization_user_tenant_erasure_index WHERE user_id=?", userId);
    dsl.execute("DELETE FROM authorization_platform_profile_assignment WHERE user_id=?", userId);
    dsl.execute("UPDATE authorization_audit SET actor_user_id=NULL WHERE actor_user_id=?", userId);
    dsl.execute("UPDATE authorization_audit SET target_id=NULL WHERE target_id=?", userId);

    UUID requestId = UUID.fromString(event.getErasureRequestId());
    dsl.execute(
        """
        UPDATE authorization_erasure_inbox
        SET state='COMPLETED',target_user_id=NULL,lease_until=NULL,redrive_requested=FALSE,last_error_class=NULL,
            completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE event_id=? AND state='PROCESSING'
        """,
        ts(now),
        eventId);
    dsl.execute(
        """
        INSERT INTO authorization_erasure_evidence(
          evidence_id,erasure_request_id,policy_version,event_code,action_categories,
          occurred_at,integrity_version)
        VALUES (?,?,?,'ERASURE_COMPLETED',?,CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')
        """,
        UUID.randomUUID(),
        requestId,
        event.getParticipantPolicyVersion(),
        ACTIONS,
        ts(now));
    dsl.execute(
        """
        INSERT INTO authorization_erasure_receipt_outbox(
          event_id,erasure_request_id,participant_policy_version,outcome,action_categories,
          state,attempt_count,next_attempt_at,occurred_at,retain_until,updated_at)
        VALUES (?,?,?,'COMPLETED',?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        UUID.randomUUID(),
        requestId,
        event.getParticipantPolicyVersion(),
        ACTIONS,
        ts(now),
        ts(now),
        ts(now.plus(java.time.Duration.ofDays(35))),
        ts(now));
    return true;
  }

  public void reschedule(UUID eventId, int attempt, Instant next, String safeErrorClass) {
    dsl.execute(
        """
        UPDATE authorization_erasure_inbox
        SET state='PENDING',attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,
            next_attempt_at=CASE WHEN redrive_requested THEN next_attempt_at
                                 ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,
            lease_until=NULL,target_user_id=NULL,
            last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,
            redrive_requested=FALSE
        WHERE event_id=? AND state='PROCESSING'
        """,
        attempt,
        ts(next),
        safeErrorClass,
        eventId);
  }

  public void exhaust(UUID eventId, int attempt, String safeErrorClass) {
    dsl.execute(
        """
        UPDATE authorization_erasure_inbox
        SET state=CASE WHEN redrive_requested THEN 'PENDING' ELSE 'EXHAUSTED' END,
            attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,
            lease_until=NULL,target_user_id=NULL,
            last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,
            redrive_requested=FALSE
        WHERE event_id=? AND state='PROCESSING'
        """,
        attempt,
        safeErrorClass,
        eventId);
  }

  private void setTenant(UUID tenantId) {
    Object value =
        dsl.fetchValue("SELECT set_config('app.current_tenant_id',CAST(? AS text),true)", tenantId);
    if (!tenantId.toString().equals(String.valueOf(value))) {
      throw new IllegalStateException("Tenant context could not be established");
    }
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }

  private record MembershipTarget(UUID tenantId, UUID membershipId) {}

  public record InboxItem(
      UUID eventId, UUID erasureRequestId, String participantPolicyVersion, int attemptCount) {}
}
