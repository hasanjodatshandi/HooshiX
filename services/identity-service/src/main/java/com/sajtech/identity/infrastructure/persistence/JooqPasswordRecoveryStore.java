package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.password.PasswordError;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.password.port.out.PreparedPasswordRecovery;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqPasswordRecoveryStore implements PasswordRecoveryStore {
  private final DSLContext dsl;

  public JooqPasswordRecoveryStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean requestAlreadyAccepted(UUID requestId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("identity_password_recovery_challenge")
            .where("request_id = ?", requestId));
  }

  @Override
  public void create(PreparedPasswordRecovery recovery) {
    dsl.execute(
        """
        UPDATE identity_password_recovery_challenge
        SET state='EXPIRED', updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE user_id=? AND state='ACTIVE'
        """,
        ts(recovery.createdAt()),
        recovery.userId());
    dsl.execute(
        """
        INSERT INTO identity_password_recovery_challenge(
          challenge_id,request_id,user_id,contact_id,verifier,verifier_key_id,state,failed_attempts,
          expires_at,created_at,updated_at)
        VALUES (?,?,?,?,?,?,'ACTIVE',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        recovery.challengeId(),
        recovery.requestId(),
        recovery.userId(),
        recovery.contactId(),
        recovery.verifier(),
        recovery.verifierKeyId(),
        ts(recovery.expiresAt()),
        ts(recovery.createdAt()),
        ts(recovery.createdAt()));
    dsl.execute(
        """
        INSERT INTO identity_notification_outbox(
          outbox_id,request_id,challenge_id,password_recovery_challenge_id,content_type,
          channel,locale,escrow_format_version,escrow_key_id,payload_nonce,payload_ciphertext,
          message_not_after,sensitive_expires_at,state,attempt_count,next_attempt_at,created_at,updated_at)
        VALUES (?,?,NULL,?,'PASSWORD_RECOVERY',?,?,1,?,?,?,
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),'PENDING',0,
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
          CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        recovery.outboxId(),
        recovery.notificationRequestId(),
        recovery.challengeId(),
        recovery.contact().channel() == RegistrationChannel.EMAIL ? "EMAIL" : "SMS",
        recovery.locale().canonical(),
        recovery.handoff().keyId(),
        recovery.handoff().nonce(),
        recovery.handoff().ciphertext(),
        ts(recovery.expiresAt()),
        ts(recovery.expiresAt()),
        ts(recovery.createdAt()),
        ts(recovery.createdAt()),
        ts(recovery.createdAt()));
    audit(
        "IDENTITY_PASSWORD_RECOVERY_REQUESTED",
        recovery.userId(),
        recovery.contactId(),
        recovery.createdAt());
  }

  @Override
  public Optional<RecoveryChallenge> findActiveByContact(String canonicalContact, Instant now) {
    return find(canonicalContact, now, false);
  }

  @Override
  public Optional<RecoveryChallenge> lockActiveByContact(String canonicalContact, Instant now) {
    return find(canonicalContact, now, true);
  }

  private Optional<RecoveryChallenge> find(String canonicalContact, Instant now, boolean lock) {
    String sql =
        """
        SELECT ch.challenge_id,ch.user_id,ch.verifier,ch.verifier_key_id,ch.expires_at,
               ch.state,ch.failed_attempts
        FROM identity_password_recovery_challenge ch
        JOIN identity_contact c ON c.contact_id=ch.contact_id AND c.user_id=ch.user_id
        JOIN identity_user u ON u.user_id=ch.user_id
        JOIN identity_credential cr ON cr.user_id=ch.user_id
        WHERE c.canonical_value=? AND c.primary_active=TRUE AND c.verified_at IS NOT NULL
          AND c.removed_at IS NULL AND u.status='ACTIVE' AND ch.state='ACTIVE'
          AND ch.expires_at>CAST(? AS TIMESTAMP WITH TIME ZONE)
        ORDER BY ch.created_at DESC,ch.challenge_id DESC LIMIT 1
        """
            + (lock ? " FOR UPDATE OF ch,c,u,cr" : "");
    return dsl.fetchOptional(sql, canonicalContact, ts(now)).map(JooqPasswordRecoveryStore::map);
  }

  @Override
  public void recordFailedProof(UUID challengeId, Instant now) {
    Record row =
        dsl.fetchOne(
            "SELECT user_id,contact_id,failed_attempts FROM identity_password_recovery_challenge WHERE challenge_id=? AND state='ACTIVE' FOR UPDATE",
            challengeId);
    if (row == null) return;
    int next = row.get("failed_attempts", Integer.class) + 1;
    dsl.execute(
        """
        UPDATE identity_password_recovery_challenge
        SET failed_attempts=?,state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE challenge_id=? AND state='ACTIVE'
        """,
        next,
        next >= 5 ? "EXHAUSTED" : "ACTIVE",
        ts(now),
        challengeId);
    audit(
        next >= 5
            ? "IDENTITY_PASSWORD_RECOVERY_PROOF_EXHAUSTED"
            : "IDENTITY_PASSWORD_RECOVERY_PROOF_REJECTED",
        row.get("user_id", UUID.class),
        row.get("contact_id", UUID.class),
        now);
  }

  @Override
  public void markUsed(UUID challengeId, UUID requestId, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_password_recovery_challenge
            SET state='USED',completed_request_id=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE challenge_id=? AND state='ACTIVE'
            """,
            requestId,
            ts(now),
            challengeId);
    if (changed != 1) {
      throw new PasswordException(
          PasswordError.INVALID_RECOVERY_PROOF, "Password recovery proof is invalid");
    }
    Record row =
        dsl.fetchOne(
            "SELECT user_id,contact_id FROM identity_password_recovery_challenge WHERE challenge_id=?",
            challengeId);
    if (row != null) {
      audit(
          "IDENTITY_PASSWORD_RECOVERY_COMPLETED",
          row.get("user_id", UUID.class),
          row.get("contact_id", UUID.class),
          now);
    }
  }

  @Override
  public boolean confirmationAlreadyCompleted(UUID requestId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("identity_password_recovery_challenge")
            .where("completed_request_id = ?", requestId));
  }

  @Override
  public Optional<RecoveryTarget> findTargetByContact(CanonicalContact contact) {
    return target(contact, false);
  }

  @Override
  public Optional<RecoveryTarget> lockTargetByContact(CanonicalContact contact) {
    return target(contact, true);
  }

  private Optional<RecoveryTarget> target(CanonicalContact contact, boolean lock) {
    return dsl.fetchOptional(
            """
            SELECT u.user_id,c.contact_id,c.contact_type,c.canonical_value,c.delivery_value
            FROM identity_contact c
            JOIN identity_user u ON u.user_id=c.user_id
            JOIN identity_credential cr ON cr.user_id=u.user_id
            WHERE c.contact_type=? AND c.canonical_value=? AND c.primary_active=TRUE
              AND c.verified_at IS NOT NULL AND c.removed_at IS NULL AND u.status='ACTIVE'
            LIMIT 1
            """
                + (lock ? " FOR UPDATE OF c,u,cr" : ""),
            contact.channel().name(),
            contact.canonicalValue())
        .map(
            row ->
                new RecoveryTarget(
                    row.get("user_id", UUID.class),
                    row.get("contact_id", UUID.class),
                    new CanonicalContact(
                        RegistrationChannel.valueOf(row.get("contact_type", String.class)),
                        row.get("canonical_value", String.class),
                        row.get("delivery_value", String.class))));
  }

  private void audit(String code, UUID userId, UUID contactId, Instant now) {
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,contact_id,occurred_at) VALUES (?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        code,
        userId,
        contactId,
        ts(now));
  }

  private static RecoveryChallenge map(Record row) {
    return new RecoveryChallenge(
        row.get("challenge_id", UUID.class),
        row.get("user_id", UUID.class),
        row.get("verifier", byte[].class),
        row.get("verifier_key_id", String.class),
        row.get("expires_at", OffsetDateTime.class).toInstant(),
        row.get("state", String.class),
        row.get("failed_attempts", Integer.class));
  }

  private static OffsetDateTime ts(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
