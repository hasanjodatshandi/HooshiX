package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.erasure.ErasureError;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.model.LegalHoldView;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqErasureStore implements ErasureStore {
  private static final String POLICY_VERSION = "1";
  private static final List<String> PARTICIPANTS =
      List.of("IDENTITY_SERVICE", "AUTHORIZATION_SERVICE", "NOTIFICATION_SERVICE", "WEB_BFF");
  private final DSLContext dsl;

  public JooqErasureStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<ErasureRequestView> find(UUID erasureRequestId) {
    return dsl.fetchOptional(
            """
            SELECT erasure_request_id,user_id,state,participant_policy_version,
                   accepted_at,completed_at
            FROM identity_erasure_request
            WHERE erasure_request_id=?
            """,
            erasureRequestId)
        .map(JooqErasureStore::map);
  }

  @Override
  public ErasureRequestView accept(UUID erasureRequestId, UUID userId, Instant now) {
    Record user =
        dsl.fetchOne("SELECT status FROM identity_user WHERE user_id=? FOR UPDATE", userId);
    if (user == null || !"ACTIVE".equals(user.get("status", String.class))) {
      throw new ErasureException(ErasureError.INVALID_SESSION, "Erasure session is invalid");
    }
    if (hasBlockingMembership(userId)) {
      throw new ErasureException(
          ErasureError.ACTIVE_MEMBERSHIP_EXISTS,
          "Active tenant membership must be resolved before erasure");
    }

    dsl.execute(
        """
        INSERT INTO identity_erasure_request(
          erasure_request_id,user_id,state,participant_policy_version,
          accepted_at,updated_at)
        VALUES (?,?,'REQUESTED',?,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        erasureRequestId,
        userId,
        POLICY_VERSION,
        ts(now),
        ts(now));
    for (String participant : PARTICIPANTS) {
      dsl.execute(
          """
          INSERT INTO identity_erasure_participant(
            erasure_request_id,participant,state,updated_at)
          VALUES (?,?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE))
          """,
          erasureRequestId,
          participant,
          ts(now));
    }

    snapshotNotificationTargets(erasureRequestId, userId, now);
    revokePendingInvitations(userId, now);
    dsl.execute(
        "UPDATE identity_user SET status='DELETING',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=?",
        ts(now),
        userId);

    UUID eventId = UUID.randomUUID();
    dsl.execute(
        """
        INSERT INTO identity_erasure_event_outbox(
          event_id,erasure_request_id,event_type,participant_policy_version,state,
          attempt_count,next_attempt_at,occurred_at,retain_until,updated_at)
        VALUES (?,?,'COMMAND',?,'PENDING',0,
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        eventId,
        erasureRequestId,
        POLICY_VERSION,
        ts(now),
        ts(now),
        ts(now.plus(java.time.Duration.ofDays(35))),
        ts(now));
    evidence(erasureRequestId, "ERASURE_ACCEPTED", "authentication_shutdown", now);
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,occurred_at) VALUES (?,'IDENTITY_ERASURE_ACCEPTED',?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        userId,
        ts(now));
    return find(erasureRequestId)
        .orElseThrow(
            () -> new IllegalStateException("Accepted erasure request could not be reloaded"));
  }

  @Override
  public ParticipantErasureTarget beginParticipant(
      UUID eventId,
      UUID erasureRequestId,
      ErasureParticipant participant,
      String participantPolicyVersion,
      String pageToken,
      Instant now) {
    Record request =
        dsl.fetchOne(
            """
            SELECT user_id,state,participant_policy_version
            FROM identity_erasure_request
            WHERE erasure_request_id=?
            FOR UPDATE
            """,
            erasureRequestId);
    if (request == null) {
      throw new ErasureException(ErasureError.NOT_FOUND, "Erasure request was not found");
    }
    String storedPolicy = request.get("participant_policy_version", String.class);
    if (!storedPolicy.equals(participantPolicyVersion)) {
      throw new ErasureException(ErasureError.REQUEST_CONFLICT, "Erasure policy conflicts");
    }
    Record hold =
        dsl.fetchOne(
            "SELECT EXISTS(SELECT 1 FROM identity_legal_hold WHERE erasure_request_id=? AND status='ACTIVE') AS active",
            erasureRequestId);
    Boolean activeHold = hold == null ? null : hold.get("active", Boolean.class);
    if (Boolean.TRUE.equals(activeHold)) {
      dsl.execute(
          "UPDATE identity_erasure_request SET state='BLOCKED_BY_LEGAL_HOLD',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state<>'COMPLETED'",
          ts(now),
          erasureRequestId);
      dsl.execute(
          "UPDATE identity_erasure_participant SET state='BLOCKED_BY_LEGAL_HOLD',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND participant=? AND state<>'COMPLETED'",
          ts(now),
          erasureRequestId,
          participant.name());
      throw new ErasureException(ErasureError.LEGAL_HOLD_ACTIVE, "Legal hold blocks erasure");
    }
    int changed =
        dsl.execute(
            """
            UPDATE identity_erasure_participant
            SET state='IN_PROGRESS',started_at=COALESCE(started_at,CAST(? AS TIMESTAMP WITH TIME ZONE)),
                updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE erasure_request_id=? AND participant=? AND state<>'COMPLETED'
            """,
            ts(now),
            ts(now),
            erasureRequestId,
            participant.name());
    if (changed == 0) {
      Record completed =
          dsl.fetchOne(
              "SELECT state FROM identity_erasure_participant WHERE erasure_request_id=? AND participant=?",
              erasureRequestId,
              participant.name());
      if (completed == null) {
        throw new ErasureException(ErasureError.FORBIDDEN, "Participant is not registered");
      }
    }
    dsl.execute(
        "UPDATE identity_erasure_request SET state='IN_PROGRESS',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state IN ('REQUESTED','FAILED_RETRYABLE','BLOCKED_BY_LEGAL_HOLD')",
        ts(now),
        erasureRequestId);

    UUID userId = request.get("user_id", UUID.class);
    if (participant == ErasureParticipant.NOTIFICATION_SERVICE) {
      UUID after = pageToken.isEmpty() ? null : UUID.fromString(pageToken);
      List<UUID> targets =
          dsl.fetch(
                  """
                  SELECT notification_id
                  FROM identity_erasure_notification_target
                  WHERE erasure_request_id=?
                    AND (?::uuid IS NULL OR notification_id>?::uuid)
                  ORDER BY notification_id
                  LIMIT 201
                  """,
                  erasureRequestId,
                  after,
                  after)
              .map(row -> row.get("notification_id", UUID.class));
      boolean complete = targets.size() <= 200;
      List<UUID> page = complete ? targets : targets.subList(0, 200);
      String next = complete || page.isEmpty() ? "" : page.get(page.size() - 1).toString();
      return new ParticipantErasureTarget(participant, null, page, next, complete);
    }
    UUID target =
        participant == ErasureParticipant.AUTHORIZATION_SERVICE
                || participant == ErasureParticipant.WEB_BFF
            ? userId
            : null;
    return new ParticipantErasureTarget(participant, target, List.of(), "", true);
  }

  @Override
  public LegalHoldView createHold(
      UUID holdId,
      UUID erasureRequestId,
      String authorityReference,
      UUID actorUserId,
      Instant now) {
    Record existing =
        dsl.fetchOne(
            "SELECT hold_id,erasure_request_id,status,policy_version,created_at,released_at,actor_user_id,authority_reference FROM identity_legal_hold WHERE hold_id=? FOR UPDATE",
            holdId);
    if (existing != null) {
      if (!erasureRequestId.equals(existing.get("erasure_request_id", UUID.class))
          || !actorUserId.equals(existing.get("actor_user_id", UUID.class))
          || !authorityReference.equals(existing.get("authority_reference", String.class))) {
        throw new ErasureException(ErasureError.REQUEST_CONFLICT, "Legal hold request conflicts");
      }
      return mapHold(existing);
    }
    Record request =
        dsl.fetchOne(
            "SELECT state FROM identity_erasure_request WHERE erasure_request_id=? FOR UPDATE",
            erasureRequestId);
    if (request == null)
      throw new ErasureException(ErasureError.NOT_FOUND, "Erasure request was not found");
    if ("COMPLETED".equals(request.get("state", String.class))) {
      throw new ErasureException(ErasureError.REQUEST_CONFLICT, "Completed erasure cannot be held");
    }
    Record started =
        dsl.fetchOne(
            "SELECT EXISTS(SELECT 1 FROM identity_erasure_participant WHERE erasure_request_id=? AND started_at IS NOT NULL) AS started",
            erasureRequestId);
    if (started != null && Boolean.TRUE.equals(started.get("started", Boolean.class))) {
      throw new ErasureException(
          ErasureError.REQUEST_CONFLICT, "Irreversible erasure processing has already started");
    }
    dsl.execute(
        "INSERT INTO identity_legal_hold(hold_id,erasure_request_id,status,authority_reference,actor_user_id,policy_version,created_at) VALUES (?,?,'ACTIVE',?,?,'1',CAST(? AS TIMESTAMP WITH TIME ZONE))",
        holdId,
        erasureRequestId,
        authorityReference,
        actorUserId,
        ts(now));
    dsl.execute(
        "UPDATE identity_erasure_request SET state='BLOCKED_BY_LEGAL_HOLD',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=?",
        ts(now),
        erasureRequestId);
    dsl.execute(
        "UPDATE identity_erasure_participant SET state='BLOCKED_BY_LEGAL_HOLD',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state<>'COMPLETED'",
        ts(now),
        erasureRequestId);
    evidence(erasureRequestId, "LEGAL_HOLD_CREATED", "legal_hold", now);
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,occurred_at) VALUES (?,'IDENTITY_LEGAL_HOLD_CREATED',?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        actorUserId,
        ts(now));
    Record created =
        dsl.fetchOne(
            "SELECT hold_id,erasure_request_id,status,policy_version,created_at,released_at FROM identity_legal_hold WHERE hold_id=?",
            holdId);
    if (created == null) {
      throw new IllegalStateException("Created legal hold could not be reloaded");
    }
    return mapHold(created);
  }

  @Override
  public LegalHoldView releaseHold(UUID holdId, UUID actorUserId, Instant now) {
    Record hold =
        dsl.fetchOne(
            "SELECT hold_id,erasure_request_id,status,policy_version,created_at,released_at FROM identity_legal_hold WHERE hold_id=? FOR UPDATE",
            holdId);
    if (hold == null)
      throw new ErasureException(ErasureError.NOT_FOUND, "Legal hold was not found");
    if ("RELEASED".equals(hold.get("status", String.class))) return mapHold(hold);
    UUID requestId = hold.get("erasure_request_id", UUID.class);
    dsl.fetchOne(
        "SELECT erasure_request_id FROM identity_erasure_request WHERE erasure_request_id=? FOR UPDATE",
        requestId);
    dsl.execute(
        "UPDATE identity_legal_hold SET status='RELEASED',released_at=CAST(? AS TIMESTAMP WITH TIME ZONE),released_by_user_id=? WHERE hold_id=? AND status='ACTIVE'",
        ts(now),
        actorUserId,
        holdId);
    dsl.execute(
        "UPDATE identity_erasure_participant SET state='PENDING',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state='BLOCKED_BY_LEGAL_HOLD'",
        ts(now),
        requestId);
    dsl.execute(
        "UPDATE identity_erasure_request SET state=CASE WHEN EXISTS(SELECT 1 FROM identity_erasure_participant WHERE erasure_request_id=? AND started_at IS NOT NULL) THEN 'IN_PROGRESS' ELSE 'REQUESTED' END,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE erasure_request_id=? AND state='BLOCKED_BY_LEGAL_HOLD'",
        requestId,
        ts(now),
        requestId);
    dsl.execute(
        "INSERT INTO identity_erasure_event_outbox(event_id,erasure_request_id,event_type,participant_policy_version,state,attempt_count,next_attempt_at,occurred_at,retain_until,updated_at) VALUES (?,?,'COMMAND','1','PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        requestId,
        ts(now),
        ts(now),
        ts(now.plus(java.time.Duration.ofDays(35))),
        ts(now));
    evidence(requestId, "LEGAL_HOLD_RELEASED", "legal_hold", now);
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,occurred_at) VALUES (?,'IDENTITY_LEGAL_HOLD_RELEASED',?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        actorUserId,
        ts(now));
    Record released =
        dsl.fetchOne(
            "SELECT hold_id,erasure_request_id,status,policy_version,created_at,released_at FROM identity_legal_hold WHERE hold_id=?",
            holdId);
    if (released == null) {
      throw new IllegalStateException("Released legal hold could not be reloaded");
    }
    return mapHold(released);
  }

  private boolean hasBlockingMembership(UUID userId) {
    Record record =
        dsl.fetchOne(
            """
            SELECT EXISTS(
              SELECT 1
              FROM identity_user_membership_query
              WHERE user_id=?
                AND membership_lifecycle IN ('ACTIVE','SUSPENDED')
                AND tenant_lifecycle IN ('PROVISIONING','ACTIVE','SUSPENDED','DELETING')
            ) AS blocking
            """,
            userId);
    return record == null || Boolean.TRUE.equals(record.get("blocking", Boolean.class));
  }

  private void revokePendingInvitations(UUID userId, Instant now) {
    List<UUID> tenants =
        dsl
            .fetch(
                """
                SELECT tenant_id
                FROM identity_invitation_query
                WHERE target_user_id=? AND state='PENDING'
                ORDER BY tenant_id,invitation_id
                FOR UPDATE
                """,
                userId)
            .map(record -> record.get("tenant_id", UUID.class))
            .stream()
            .distinct()
            .toList();
    try {
      for (UUID tenantId : tenants) {
        setTenant(tenantId);
        dsl.execute(
            """
            UPDATE identity_tenant_invitation
            SET state='REVOKED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE tenant_id=? AND target_user_id=? AND state='PENDING'
            """,
            ts(now),
            tenantId,
            userId);
      }
    } finally {
      dsl.fetchValue("SELECT set_config('app.current_tenant_id','',true)");
    }
    dsl.execute(
        """
        UPDATE identity_invitation_query
        SET state='REVOKED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE target_user_id=? AND state='PENDING'
        """,
        ts(now),
        userId);
  }

  private void snapshotNotificationTargets(UUID erasureRequestId, UUID userId, Instant now) {
    dsl.execute(
        """
        INSERT INTO identity_erasure_notification_target(
          erasure_request_id,notification_id,created_at)
        SELECT ?,n.notification_id,CAST(? AS TIMESTAMP WITH TIME ZONE)
        FROM identity_notification_outbox n
        LEFT JOIN registration_challenge registration
          ON registration.challenge_id=n.challenge_id
        LEFT JOIN identity_password_recovery_challenge recovery
          ON recovery.challenge_id=n.password_recovery_challenge_id
        LEFT JOIN identity_contact_verification_challenge verification
          ON verification.challenge_id=n.contact_verification_challenge_id
        LEFT JOIN identity_contact verified_contact
          ON verified_contact.contact_id=verification.contact_id
        WHERE n.notification_id IS NOT NULL
          AND (registration.user_id=? OR recovery.user_id=? OR verified_contact.user_id=?)
        ON CONFLICT DO NOTHING
        """,
        erasureRequestId,
        ts(now),
        userId,
        userId,
        userId);
  }

  private void evidence(
      UUID erasureRequestId, String eventCode, String actionCategory, Instant now) {
    dsl.execute(
        """
        INSERT INTO identity_erasure_evidence(
          evidence_id,erasure_request_id,service,policy_version,event_code,
          action_categories,occurred_at,integrity_version)
        VALUES (?,?,'identity-service',?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),'v1')
        """,
        UUID.randomUUID(),
        erasureRequestId,
        POLICY_VERSION,
        eventCode,
        actionCategory,
        ts(now));
  }

  private void setTenant(UUID tenantId) {
    Object value =
        dsl.fetchValue(
            "SELECT set_config('app.current_tenant_id', CAST(? AS text), true)", tenantId);
    if (!tenantId.toString().equals(String.valueOf(value))) {
      throw new IllegalStateException("Tenant context could not be established");
    }
  }

  private static ErasureRequestView map(Record record) {
    OffsetDateTime acceptedAt = record.get("accepted_at", OffsetDateTime.class);
    OffsetDateTime completedAt = record.get("completed_at", OffsetDateTime.class);
    return new ErasureRequestView(
        record.get("erasure_request_id", UUID.class),
        record.get("user_id", UUID.class),
        record.get("state", String.class),
        record.get("participant_policy_version", String.class),
        acceptedAt.toInstant(),
        completedAt == null ? null : completedAt.toInstant());
  }

  private static LegalHoldView mapHold(Record record) {
    OffsetDateTime released = record.get("released_at", OffsetDateTime.class);
    return new LegalHoldView(
        record.get("hold_id", UUID.class),
        record.get("erasure_request_id", UUID.class),
        record.get("status", String.class),
        record.get("policy_version", String.class),
        record.get("created_at", OffsetDateTime.class).toInstant(),
        released == null ? null : released.toInstant());
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
