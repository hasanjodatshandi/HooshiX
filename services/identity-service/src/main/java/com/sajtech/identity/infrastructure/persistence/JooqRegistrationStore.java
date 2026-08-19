package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.RegistrationStore;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqRegistrationStore implements RegistrationStore {
  private final DSLContext dsl;

  public JooqRegistrationStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<CommandDedupRecord> findDedup(UUID requestId) {
    return dsl.fetchOptional(
            """
      SELECT request_id, operation, intent_fingerprint, fingerprint_version, fingerprint_key_id, outcome, created_at
      FROM identity_command_dedup WHERE request_id = ?
      """,
            requestId)
        .map(this::mapDedup);
  }

  @Override
  public void lockContactKey(CanonicalContact contact) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        contact.channel().name() + ":" + contact.canonicalValue());
  }

  @Override
  public boolean verifiedContactExists(CanonicalContact contact) {
    return dsl.fetchOptional(
            """
            SELECT 1 FROM identity_contact
            WHERE contact_type = ? AND canonical_value = ? AND verified_at IS NOT NULL AND removed_at IS NULL
            LIMIT 1
            """,
            contact.channel().name(),
            contact.canonicalValue())
        .isPresent();
  }

  @Override
  public Optional<ReservationRecord> findReservation(CanonicalContact contact) {
    return dsl.fetchOptional(
            """
      SELECT r.user_id, r.contact_id, r.challenge_id, c.locale, ct.delivery_value, r.expires_at, c.last_sent_at
      FROM registration_reservation r
      JOIN registration_challenge c ON c.challenge_id = r.challenge_id
      JOIN identity_contact ct ON ct.contact_id = r.contact_id
      WHERE r.contact_type = ? AND r.canonical_value = ?
      """,
            contact.channel().name(),
            contact.canonicalValue())
        .map(this::mapReservation);
  }

  @Override
  public Optional<LockedChallenge> lockChallenge(UUID challengeId) {
    return dsl.fetchOptional(
            """
      SELECT user_id, contact_id, challenge_id, verifier, verifier_key_id, failed_attempts, expires_at, state
      FROM registration_challenge WHERE challenge_id = ? FOR UPDATE
      """,
            challengeId)
        .map(
            r ->
                new LockedChallenge(
                    r.get("user_id", UUID.class),
                    r.get("contact_id", UUID.class),
                    r.get("challenge_id", UUID.class),
                    r.get("verifier", byte[].class),
                    r.get("verifier_key_id", String.class),
                    r.get("failed_attempts", Integer.class),
                    r.get("expires_at", OffsetDateTime.class).toInstant(),
                    r.get("state", String.class)));
  }

  @Override
  public void expireChallenge(UUID challengeId, Instant now) {
    dsl.execute(
        "UPDATE registration_challenge SET state = 'EXPIRED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE challenge_id = ? AND state = 'ACTIVE'",
        ts(now),
        challengeId);
  }

  @Override
  public void insertRegistration(PreparedRegistration p) {
    dsl.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?, 'PENDING', CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS TIMESTAMP WITH TIME ZONE))",
        p.userId(),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        "INSERT INTO identity_profile(user_id,first_name,last_name,father_name,created_at,updated_at) VALUES (?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        p.userId(),
        p.profile().firstName(),
        p.profile().lastName(),
        p.profile().fatherName(),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        "INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,created_at,updated_at) VALUES (?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        p.contactId(),
        p.userId(),
        p.contact().channel().name(),
        p.contact().canonicalValue(),
        p.contact().deliveryValue(),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        "INSERT INTO identity_credential(user_id,password_hash,algorithm,created_at,updated_at) VALUES (?,?,'ARGON2ID',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        p.userId(),
        p.passwordHash(),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        """
      INSERT INTO registration_challenge(challenge_id,user_id,contact_id,verifier,verifier_key_id,locale,state,failed_attempts,expires_at,last_sent_at,created_at,updated_at)
      VALUES (?,?,?,?,?,?,'ACTIVE',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
      """,
        p.challengeId(),
        p.userId(),
        p.contactId(),
        p.challengeVerifier(),
        p.challengeKeyId(),
        p.locale().canonical(),
        ts(p.challengeExpiresAt()),
        ts(p.createdAt()),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        """
      INSERT INTO registration_reservation(contact_type,canonical_value,user_id,contact_id,challenge_id,expires_at,updated_at)
      VALUES (?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
      ON CONFLICT (contact_type,canonical_value) DO UPDATE SET user_id=EXCLUDED.user_id,contact_id=EXCLUDED.contact_id,challenge_id=EXCLUDED.challenge_id,expires_at=EXCLUDED.expires_at,updated_at=EXCLUDED.updated_at
      """,
        p.contact().channel().name(),
        p.contact().canonicalValue(),
        p.userId(),
        p.contactId(),
        p.challengeId(),
        ts(p.challengeExpiresAt()),
        ts(p.createdAt()));
    insertOutbox(
        p.outboxId(),
        p.notificationRequestId(),
        p.challengeId(),
        p.contact().channel(),
        p.locale(),
        p.handoff(),
        p.createdAt(),
        p.challengeExpiresAt());
    audit("IDENTITY_REGISTRATION_CREATED", p.userId(), p.contactId(), p.createdAt());
  }

  @Override
  public void replaceChallenge(
      CanonicalContact contact, ReservationRecord reservation, PreparedChallengeReplacement p) {
    dsl.execute(
        "UPDATE registration_challenge SET state='REPLACED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE challenge_id=? AND state='ACTIVE'",
        ts(p.createdAt()),
        p.oldChallengeId());
    dsl.execute(
        """
      INSERT INTO registration_challenge(challenge_id,user_id,contact_id,verifier,verifier_key_id,locale,state,failed_attempts,expires_at,last_sent_at,created_at,updated_at)
      VALUES (?,?,?,?,?,?,'ACTIVE',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
      """,
        p.newChallengeId(),
        reservation.userId(),
        reservation.contactId(),
        p.verifier(),
        p.verifierKeyId(),
        reservation.locale().canonical(),
        ts(p.expiresAt()),
        ts(p.createdAt()),
        ts(p.createdAt()),
        ts(p.createdAt()));
    dsl.execute(
        "UPDATE registration_reservation SET challenge_id=?,expires_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE contact_type=? AND canonical_value=? AND challenge_id=?",
        p.newChallengeId(),
        ts(p.expiresAt()),
        ts(p.createdAt()),
        contact.channel().name(),
        contact.canonicalValue(),
        p.oldChallengeId());
    insertOutbox(
        p.outboxId(),
        p.notificationRequestId(),
        p.newChallengeId(),
        contact.channel(),
        reservation.locale(),
        p.handoff(),
        p.createdAt(),
        p.expiresAt());
    audit(
        "IDENTITY_REGISTRATION_CHALLENGE_REPLACED",
        reservation.userId(),
        reservation.contactId(),
        p.createdAt());
  }

  @Override
  public void recordFailedProof(
      UUID challengeId, int failedAttempts, boolean exhausted, Instant now) {
    dsl.execute(
        "UPDATE registration_challenge SET failed_attempts=?,state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE challenge_id=? AND state='ACTIVE'",
        failedAttempts,
        exhausted ? "EXHAUSTED" : "ACTIVE",
        ts(now),
        challengeId);
  }

  @Override
  public void confirm(UUID userId, UUID contactId, UUID challengeId, Instant now) {
    dsl.execute(
        "UPDATE registration_challenge SET state='USED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE challenge_id=? AND state='ACTIVE'",
        ts(now),
        challengeId);
    dsl.execute(
        """
      UPDATE identity_contact c SET verified_at=CAST(? AS TIMESTAMP WITH TIME ZONE),
        primary_active=NOT EXISTS(SELECT 1 FROM identity_contact p WHERE p.user_id=c.user_id AND p.primary_active=TRUE AND p.removed_at IS NULL),
        updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
      WHERE c.contact_id=? AND c.user_id=? AND c.verified_at IS NULL AND c.removed_at IS NULL
      """,
        ts(now),
        ts(now),
        contactId,
        userId);
    dsl.execute(
        "UPDATE identity_user SET status='ACTIVE',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=? AND status='PENDING' AND EXISTS(SELECT 1 FROM identity_profile p WHERE p.user_id=?) AND EXISTS(SELECT 1 FROM identity_credential cr WHERE cr.user_id=?) AND EXISTS(SELECT 1 FROM identity_contact c WHERE c.user_id=? AND c.verified_at IS NOT NULL AND c.removed_at IS NULL)",
        ts(now),
        userId,
        userId,
        userId,
        userId);
    dsl.execute("DELETE FROM registration_reservation WHERE challenge_id=?", challengeId);
    audit("IDENTITY_REGISTRATION_CONFIRMED", userId, contactId, now);
  }

  @Override
  public boolean tryInsertDedup(
      UUID requestId,
      String operation,
      byte[] fingerprint,
      String version,
      String keyId,
      String outcome,
      Instant now) {
    return dsl.execute(
            """
      INSERT INTO identity_command_dedup(request_id,operation,intent_fingerprint,fingerprint_version,fingerprint_key_id,outcome,created_at)
      VALUES (?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT (request_id) DO NOTHING
      """,
            requestId,
            operation,
            fingerprint,
            version,
            keyId,
            outcome,
            ts(now))
        == 1;
  }

  @Override
  public int deleteDedupBefore(Instant cutoff, int batch) {
    if (batch <= 0 || batch > 256) {
      throw new IllegalArgumentException("Idempotency cleanup batch is invalid");
    }
    return dsl.execute(
        """
        DELETE FROM identity_command_dedup d
        WHERE d.request_id IN (
          SELECT request_id
          FROM identity_command_dedup
          WHERE created_at < CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY created_at, request_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        """,
        ts(cutoff),
        batch);
  }

  private void insertOutbox(
      UUID outboxId,
      UUID requestId,
      UUID challengeId,
      RegistrationChannel channel,
      RegistrationLocale locale,
      EncryptedHandoff handoff,
      Instant now,
      Instant expiresAt) {
    dsl.execute(
        """
      INSERT INTO identity_notification_outbox(outbox_id,request_id,challenge_id,channel,locale,escrow_format_version,escrow_key_id,payload_nonce,payload_ciphertext,message_not_after,sensitive_expires_at,state,attempt_count,next_attempt_at,created_at,updated_at)
      VALUES (?,?,?,?,?,1,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
      """,
        outboxId,
        requestId,
        challengeId,
        channel == RegistrationChannel.EMAIL ? "EMAIL" : "SMS",
        locale.canonical(),
        handoff.keyId(),
        handoff.nonce(),
        handoff.ciphertext(),
        ts(expiresAt),
        ts(expiresAt),
        ts(now),
        ts(now),
        ts(now));
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

  private CommandDedupRecord mapDedup(Record r) {
    return new CommandDedupRecord(
        r.get("request_id", UUID.class),
        r.get("operation", String.class),
        r.get("intent_fingerprint", byte[].class),
        r.get("fingerprint_version", String.class),
        r.get("fingerprint_key_id", String.class),
        r.get("outcome", String.class),
        r.get("created_at", OffsetDateTime.class).toInstant());
  }

  private ReservationRecord mapReservation(Record r) {
    return new ReservationRecord(
        r.get("user_id", UUID.class),
        r.get("contact_id", UUID.class),
        r.get("challenge_id", UUID.class),
        RegistrationLocale.valueOf(
            r.get("locale", String.class).toUpperCase(java.util.Locale.ROOT)),
        r.get("delivery_value", String.class),
        r.get("expires_at", OffsetDateTime.class).toInstant(),
        r.get("last_sent_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
