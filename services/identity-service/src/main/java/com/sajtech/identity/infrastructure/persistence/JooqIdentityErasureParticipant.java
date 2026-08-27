package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
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

public final class JooqIdentityErasureParticipant {
  private static final String ACTIONS =
      "authentication_secret,contact,external_identity,mfa_secret,profile,tenant_membership";
  private final DSLContext dsl;
  private final ErasureStore coordinator;

  public JooqIdentityErasureParticipant(DSLContext dsl, ErasureStore coordinator) {
    this.dsl = dsl;
    this.coordinator = coordinator;
  }

  public void receive(ErasureCommandEvent event, Instant now) {
    dsl.execute(
        """
        INSERT INTO identity_erasure_command_inbox(
          event_id,erasure_request_id,participant_policy_version,state,attempt_count,
          next_attempt_at,received_at,retain_until)
        VALUES (?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        ON CONFLICT(erasure_request_id) DO UPDATE SET
          state=CASE WHEN identity_erasure_command_inbox.state IN ('PENDING','EXHAUSTED')
                     THEN 'PENDING' ELSE identity_erasure_command_inbox.state END,
          attempt_count=CASE WHEN identity_erasure_command_inbox.state IN ('PENDING','EXHAUSTED')
                             THEN 0 ELSE identity_erasure_command_inbox.attempt_count END,
          next_attempt_at=CASE WHEN identity_erasure_command_inbox.state IN ('PENDING','PROCESSING','EXHAUSTED')
                               THEN EXCLUDED.next_attempt_at ELSE identity_erasure_command_inbox.next_attempt_at END,
          lease_until=CASE WHEN identity_erasure_command_inbox.state IN ('PENDING','EXHAUSTED')
                           THEN NULL ELSE identity_erasure_command_inbox.lease_until END,
          last_error_class=CASE WHEN identity_erasure_command_inbox.state IN ('PENDING','EXHAUSTED')
                                THEN NULL ELSE identity_erasure_command_inbox.last_error_class END,
          retain_until=GREATEST(identity_erasure_command_inbox.retain_until,EXCLUDED.retain_until),
          redrive_requested=(identity_erasure_command_inbox.state='PROCESSING')
        WHERE identity_erasure_command_inbox.event_id<>EXCLUDED.event_id
          AND identity_erasure_command_inbox.state<>'COMPLETED'
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
            FROM identity_erasure_command_inbox
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
                  "UPDATE identity_erasure_command_inbox SET state='PROCESSING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=?",
                  ts(now.plus(lease)),
                  eventId);
              return new InboxItem(
                  eventId,
                  row.get("erasure_request_id", UUID.class),
                  row.get("participant_policy_version", String.class),
                  row.get("attempt_count", Integer.class));
            });
  }

  public void erase(InboxItem item, Instant now) {
    coordinator.beginParticipant(
        item.eventId(),
        item.erasureRequestId(),
        ErasureParticipant.IDENTITY_SERVICE,
        item.participantPolicyVersion(),
        "",
        now);
    Record request =
        dsl.fetchOne(
            "SELECT user_id FROM identity_erasure_request WHERE erasure_request_id=? FOR UPDATE",
            item.erasureRequestId());
    if (request == null) {
      throw new IllegalStateException("Identity erasure request disappeared during processing");
    }
    UUID userId = request.get("user_id", UUID.class);
    if (userId == null) {
      throw new IllegalStateException("Identity erasure request has no user authority");
    }

    deleteInvitations(userId);
    deleteMemberships(userId);
    dsl.execute(
        "UPDATE identity_authorization_outbox SET state=CASE WHEN state IN ('PENDING','DISPATCHING') THEN 'FAILED' ELSE state END,user_id=NULL,lease_until=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=?",
        ts(now),
        userId);
    dsl.execute("DELETE FROM identity_user_tenant_preference WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_tenant_command_dedup WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_profile_command_dedup WHERE user_id=?", userId);

    dsl.execute("DELETE FROM identity_mfa_login_challenge WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_mfa_recovery_code WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_totp_pending_enrollment WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_totp_enrollment WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_refresh_family WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_external_identity WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_oidc_evidence WHERE result_user_id=?", userId);

    dsl.execute(
        """
        DELETE FROM identity_notification_outbox n
        WHERE n.challenge_id IN (SELECT challenge_id FROM registration_challenge WHERE user_id=?)
           OR n.password_recovery_challenge_id IN (
                SELECT challenge_id FROM identity_password_recovery_challenge WHERE user_id=?)
           OR n.contact_verification_challenge_id IN (
                SELECT v.challenge_id FROM identity_contact_verification_challenge v
                JOIN identity_contact c ON c.contact_id=v.contact_id WHERE c.user_id=?)
        """,
        userId,
        userId,
        userId);
    dsl.execute("DELETE FROM registration_reservation WHERE user_id=?", userId);
    dsl.execute("DELETE FROM registration_challenge WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_password_recovery_challenge WHERE user_id=?", userId);
    dsl.execute(
        "DELETE FROM identity_contact_verification_challenge WHERE contact_id IN (SELECT contact_id FROM identity_contact WHERE user_id=?)",
        userId);
    dsl.execute(
        "UPDATE identity_security_audit SET user_id=NULL,contact_id=NULL WHERE user_id=? OR contact_id IN (SELECT contact_id FROM identity_contact WHERE user_id=?)",
        userId,
        userId);
    dsl.execute("DELETE FROM identity_contact WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_credential WHERE user_id=?", userId);
    dsl.execute("DELETE FROM identity_profile WHERE user_id=?", userId);
    dsl.execute(
        "UPDATE identity_user SET status='DELETED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=? AND status='DELETING'",
        ts(now),
        userId);

    dsl.execute(
        "UPDATE identity_erasure_command_inbox SET state='COMPLETED',lease_until=NULL,redrive_requested=FALSE,last_error_class=NULL,completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE event_id=? AND state='PROCESSING'",
        ts(now),
        item.eventId());
    dsl.execute(
        "INSERT INTO identity_erasure_evidence(evidence_id,erasure_request_id,service,policy_version,event_code,action_categories,occurred_at,integrity_version) VALUES (?,?,'identity-service',?,'ERASURE_COMPLETED',?,CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')",
        UUID.randomUUID(),
        item.erasureRequestId(),
        item.participantPolicyVersion(),
        ACTIONS,
        ts(now));
    UUID receiptId = UUID.randomUUID();
    dsl.execute(
        """
        INSERT INTO identity_erasure_event_outbox(
          event_id,erasure_request_id,event_type,participant_policy_version,participant,outcome,
          action_categories,state,attempt_count,next_attempt_at,occurred_at,retain_until,updated_at)
        VALUES (?,?,'RECEIPT',?,'IDENTITY_SERVICE','COMPLETED',?,'PENDING',0,
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        receiptId,
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
        "UPDATE identity_erasure_command_inbox SET state='PENDING',attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,next_attempt_at=CASE WHEN redrive_requested THEN next_attempt_at ELSE CAST(? AS TIMESTAMP WITH TIME ZONE) END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
        attempt,
        ts(next),
        error,
        eventId);
  }

  public void exhaust(UUID eventId, int attempt, String error) {
    dsl.execute(
        "UPDATE identity_erasure_command_inbox SET state=CASE WHEN redrive_requested THEN 'PENDING' ELSE 'EXHAUSTED' END,attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'",
        attempt,
        error,
        eventId);
  }

  private void deleteInvitations(UUID userId) {
    List<TenantItem> invitations =
        dsl
            .fetch(
                "SELECT tenant_id,invitation_id FROM identity_invitation_user_erasure_index WHERE user_id=? ORDER BY tenant_id,invitation_id,relation FOR UPDATE",
                userId)
            .map(
                row ->
                    new TenantItem(
                        row.get("tenant_id", UUID.class), row.get("invitation_id", UUID.class)))
            .stream()
            .distinct()
            .toList();
    try {
      for (TenantItem invitation : invitations) {
        setTenant(invitation.tenantId());
        dsl.execute(
            "DELETE FROM identity_tenant_invitation WHERE tenant_id=? AND invitation_id=?",
            invitation.tenantId(),
            invitation.itemId());
      }
    } finally {
      clearTenant();
    }
    dsl.execute("DELETE FROM identity_invitation_query WHERE target_user_id=?", userId);
    dsl.execute("DELETE FROM identity_invitation_user_erasure_index WHERE user_id=?", userId);
  }

  private void deleteMemberships(UUID userId) {
    List<TenantItem> memberships =
        dsl.fetch(
                "SELECT tenant_id,membership_id FROM identity_user_membership_query WHERE user_id=? ORDER BY tenant_id,membership_id FOR UPDATE",
                userId)
            .map(
                row ->
                    new TenantItem(
                        row.get("tenant_id", UUID.class), row.get("membership_id", UUID.class)));
    try {
      for (TenantItem membership : memberships) {
        dsl.execute(
            "DELETE FROM identity_membership_removal_intent WHERE membership_id=? OR requested_by_user_id=?",
            membership.itemId(),
            userId);
        setTenant(membership.tenantId());
        dsl.execute(
            "DELETE FROM identity_tenant_membership WHERE tenant_id=? AND membership_id=? AND user_id=?",
            membership.tenantId(),
            membership.itemId(),
            userId);
      }
    } finally {
      clearTenant();
    }
    dsl.execute("DELETE FROM identity_user_membership_query WHERE user_id=?", userId);
  }

  private void setTenant(UUID tenantId) {
    Object value =
        dsl.fetchValue("SELECT set_config('app.current_tenant_id',CAST(? AS text),true)", tenantId);
    if (!tenantId.toString().equals(String.valueOf(value))) {
      throw new IllegalStateException("Tenant context could not be established");
    }
  }

  private void clearTenant() {
    dsl.fetchValue("SELECT set_config('app.current_tenant_id','',true)");
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private record TenantItem(UUID tenantId, UUID itemId) {}

  public record InboxItem(
      UUID eventId, UUID erasureRequestId, String participantPolicyVersion, int attemptCount) {}
}
