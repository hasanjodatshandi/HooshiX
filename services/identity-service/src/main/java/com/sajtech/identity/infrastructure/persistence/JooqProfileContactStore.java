package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.profile.ProfileError;
import com.sajtech.identity.application.profile.ProfileException;
import com.sajtech.identity.application.profile.model.*;
import com.sajtech.identity.application.profile.port.out.ProfileContactStore;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.*;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqProfileContactStore implements ProfileContactStore {
  private final DSLContext dsl;

  public JooqProfileContactStore(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl);
  }

  @Override
  public ProfileRecord findProfile(UUID userId) {
    var r =
        dsl.fetchOne(
            "SELECT user_id,first_name,last_name,father_name FROM identity_profile WHERE user_id=?",
            userId);
    if (r == null) return null;
    return new ProfileRecord(
        r.get("user_id", UUID.class),
        r.get("first_name", String.class),
        r.get("last_name", String.class),
        r.get("father_name", String.class));
  }

  @Override
  public void updateProfile(
      UUID userId, String firstName, String lastName, String fatherName, Instant updatedAt) {
    int changed =
        dsl.execute(
            "UPDATE identity_profile SET first_name=?,last_name=?,father_name=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=?",
            firstName,
            lastName,
            fatherName,
            ts(updatedAt),
            userId);
    if (changed != 1) throw new ProfileException(ProfileError.NOT_FOUND, "Profile was not found");
  }

  @Override
  public List<ContactRecord> findContacts(UUID userId, int limit) {
    if (limit <= 0 || limit > 11)
      throw new IllegalArgumentException("Contact query limit is invalid");
    return dsl.fetch(
            """
            SELECT contact_id,contact_type,delivery_value,verified_at,primary_active
            FROM identity_contact
            WHERE user_id=? AND removed_at IS NULL
            ORDER BY created_at,contact_id
            LIMIT ?
            """,
            userId,
            limit)
        .map(
            r ->
                new ContactRecord(
                    r.get("contact_id", UUID.class),
                    r.get("contact_type", String.class),
                    r.get("delivery_value", String.class),
                    r.get("verified_at") != null,
                    Boolean.TRUE.equals(r.get("primary_active", Boolean.class))));
  }

  @Override
  public void lockUser(UUID userId) {
    if (dsl.fetchOptional("SELECT 1 FROM identity_user WHERE user_id=? FOR UPDATE", userId)
        .isEmpty()) {
      throw new ProfileException(ProfileError.NOT_FOUND, "User was not found");
    }
  }

  @Override
  public void lockContactKey(CanonicalContact contact) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        contact.channel().name() + ":" + contact.canonicalValue());
  }

  @Override
  public boolean contactKeyUnavailable(
      CanonicalContact contact, UUID allowedContactId, Instant now) {
    boolean contactExists =
        (allowedContactId == null
                ? dsl.fetchOptional(
                    """
                    SELECT 1 FROM identity_contact c
                    WHERE c.contact_type=? AND c.canonical_value=? AND c.removed_at IS NULL
                      AND (
                        c.verified_at IS NOT NULL
                        OR EXISTS (
                          SELECT 1 FROM identity_contact_verification_challenge ch
                          WHERE ch.contact_id=c.contact_id AND ch.state='ACTIVE'
                            AND ch.expires_at>CAST(? AS TIMESTAMP WITH TIME ZONE)
                        )
                      )
                    LIMIT 1
                    """,
                    contact.channel().name(),
                    contact.canonicalValue(),
                    ts(now))
                : dsl.fetchOptional(
                    """
                    SELECT 1 FROM identity_contact c
                    WHERE c.contact_type=? AND c.canonical_value=? AND c.removed_at IS NULL
                      AND c.contact_id<>?
                      AND (
                        c.verified_at IS NOT NULL
                        OR EXISTS (
                          SELECT 1 FROM identity_contact_verification_challenge ch
                          WHERE ch.contact_id=c.contact_id AND ch.state='ACTIVE'
                            AND ch.expires_at>CAST(? AS TIMESTAMP WITH TIME ZONE)
                        )
                      )
                    LIMIT 1
                    """,
                    contact.channel().name(),
                    contact.canonicalValue(),
                    allowedContactId,
                    ts(now)))
            .isPresent();
    if (contactExists) return true;
    return dsl.fetchOptional(
            """
            SELECT 1 FROM registration_reservation
            WHERE contact_type=? AND canonical_value=?
              AND expires_at>CAST(? AS TIMESTAMP WITH TIME ZONE)
            LIMIT 1
            """,
            contact.channel().name(),
            contact.canonicalValue(),
            ts(now))
        .isPresent();
  }

  @Override
  public int countActiveContacts(UUID userId) {
    return dsl.fetchCount(
        dsl.selectOne().from("identity_contact").where("user_id=? AND removed_at IS NULL", userId));
  }

  @Override
  public void insertContactChallenge(UUID userId, PreparedContactChallenge p) {
    dsl.execute(
        """
        INSERT INTO identity_contact(
          contact_id,user_id,contact_type,canonical_value,delivery_value,created_at,updated_at)
        VALUES (?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        p.contactId(),
        userId,
        p.contact().channel().name(),
        p.contact().canonicalValue(),
        p.contact().deliveryValue(),
        ts(p.now()),
        ts(p.now()));
    insertChallengeAndOutbox(p);
  }

  @Override
  public Optional<LockedContactChallenge> lockLatestChallenge(UUID userId, UUID contactId) {
    return dsl.fetchOptional(
            """
            SELECT ch.challenge_id,ch.contact_id,c.user_id,ch.verifier,ch.verifier_key_id,
                   ch.state,ch.failed_attempts,ch.expires_at,ch.last_sent_at,
                   c.contact_type,c.delivery_value,ch.locale
            FROM identity_contact_verification_challenge ch
            JOIN identity_contact c ON c.contact_id=ch.contact_id
            WHERE c.user_id=? AND c.contact_id=? AND c.removed_at IS NULL
            ORDER BY ch.created_at DESC,ch.challenge_id DESC
            LIMIT 1 FOR UPDATE OF ch,c
            """,
            userId,
            contactId)
        .map(JooqProfileContactStore::mapChallenge);
  }

  @Override
  public void replaceChallenge(LockedContactChallenge previous, PreparedContactChallenge prepared) {
    dsl.execute(
        """
        UPDATE identity_contact_verification_challenge
        SET state='REPLACED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE challenge_id=? AND state<>'USED'
        """,
        ts(prepared.now()),
        previous.challengeId());
    dsl.execute(
        """
        UPDATE identity_notification_outbox
        SET state='FAILED_PERMANENT',payload_nonce=NULL,payload_ciphertext=NULL,
            claimed_until=NULL,last_error_class='CHALLENGE_REPLACED',
            updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE contact_verification_challenge_id=? AND state IN ('PENDING','CLAIMED')
        """,
        ts(prepared.now()),
        previous.challengeId());
    insertChallengeAndOutbox(prepared);
  }

  @Override
  public void recordFailedProof(UUID challengeId, int failures, boolean exhausted, Instant now) {
    dsl.execute(
        """
        UPDATE identity_contact_verification_challenge
        SET failed_attempts=?,state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE challenge_id=? AND state='ACTIVE'
        """,
        failures,
        exhausted ? "EXHAUSTED" : "ACTIVE",
        ts(now),
        challengeId);
  }

  @Override
  public void confirmContact(LockedContactChallenge challenge, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_contact
            SET verified_at=CAST(? AS TIMESTAMP WITH TIME ZONE),
                primary_active=NOT EXISTS (
                  SELECT 1 FROM identity_contact
                  WHERE user_id=? AND primary_active=TRUE AND removed_at IS NULL
                ),
                updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE contact_id=? AND user_id=? AND removed_at IS NULL AND verified_at IS NULL
            """,
            ts(now),
            challenge.userId(),
            ts(now),
            challenge.contactId(),
            challenge.userId());
    if (changed != 1) throw new ProfileException(ProfileError.NOT_FOUND, "Contact was not found");
    dsl.execute(
        "UPDATE identity_contact_verification_challenge SET state='USED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE challenge_id=? AND state='ACTIVE'",
        ts(now),
        challenge.challengeId());
  }

  @Override
  public boolean setPrimary(UUID userId, UUID contactId, Instant now) {
    var target =
        dsl.fetchOptional(
            """
            SELECT primary_active FROM identity_contact
            WHERE user_id=? AND contact_id=? AND removed_at IS NULL AND verified_at IS NOT NULL
            FOR UPDATE
            """,
            userId,
            contactId);
    if (target.isEmpty()) {
      throw new ProfileException(ProfileError.CONTACT_NOT_VERIFIED, "Contact is not verified");
    }
    if (Boolean.TRUE.equals(target.get().get("primary_active", Boolean.class))) return true;
    dsl.execute(
        "UPDATE identity_contact SET primary_active=FALSE,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=? AND primary_active=TRUE AND removed_at IS NULL",
        ts(now),
        userId);
    return dsl.execute(
            "UPDATE identity_contact SET primary_active=TRUE,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE contact_id=? AND user_id=? AND removed_at IS NULL AND verified_at IS NOT NULL",
            ts(now),
            contactId,
            userId)
        == 1;
  }

  @Override
  public boolean remove(UUID userId, UUID contactId, Instant now) {
    var target =
        dsl.fetchOptional(
            """
            SELECT primary_active,verified_at FROM identity_contact
            WHERE user_id=? AND contact_id=? AND removed_at IS NULL FOR UPDATE
            """,
            userId,
            contactId);
    if (target.isEmpty()) return false;
    if (Boolean.TRUE.equals(target.get().get("primary_active", Boolean.class))) {
      throw new ProfileException(
          ProfileError.PRIMARY_CONTACT_REQUIRED, "Primary contact cannot be removed");
    }
    if (target.get().get("verified_at") != null) {
      int verified =
          dsl.fetch(
                  "SELECT contact_id FROM identity_contact WHERE user_id=? AND removed_at IS NULL AND verified_at IS NOT NULL FOR UPDATE",
                  userId)
              .size();
      if (verified <= 1) {
        throw new ProfileException(
            ProfileError.PRIMARY_CONTACT_REQUIRED, "A verified contact must remain");
      }
    }
    return dsl.execute(
            "UPDATE identity_contact SET removed_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE user_id=? AND contact_id=? AND removed_at IS NULL AND primary_active=FALSE",
            ts(now),
            ts(now),
            userId,
            contactId)
        == 1;
  }

  @Override
  public Optional<ProfileCommandRecord> findCommand(UUID requestId) {
    return dsl.fetchOptional(
            """
            SELECT request_id,user_id,operation,intent_fingerprint,fingerprint_version,
                   fingerprint_key_id,outcome,result_id,created_at
            FROM identity_profile_command_dedup WHERE request_id=?
            """,
            requestId)
        .map(JooqProfileContactStore::mapCommand);
  }

  @Override
  public boolean tryInsertCommand(
      UUID requestId,
      UUID userId,
      String operation,
      FingerprintDigest fingerprint,
      String outcome,
      UUID resultId,
      Instant now) {
    return dsl.execute(
            """
            INSERT INTO identity_profile_command_dedup(
              request_id,user_id,operation,intent_fingerprint,fingerprint_version,
              fingerprint_key_id,outcome,result_id,created_at)
            VALUES (?,?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))
            ON CONFLICT (request_id) DO NOTHING
            """,
            requestId,
            userId,
            operation,
            fingerprint.value(),
            fingerprint.version(),
            fingerprint.keyId(),
            outcome,
            resultId,
            ts(now))
        == 1;
  }

  @Override
  public int deleteCommandsBefore(Instant cutoff, int batch) {
    if (batch <= 0 || batch > 256) throw new IllegalArgumentException("Dedup batch is invalid");
    return dsl.execute(
        """
        DELETE FROM identity_profile_command_dedup d
        WHERE d.request_id IN (
          SELECT request_id FROM identity_profile_command_dedup
          WHERE created_at < CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY created_at,request_id LIMIT ?
        )
        """,
        ts(cutoff),
        batch);
  }

  private void insertChallengeAndOutbox(PreparedContactChallenge p) {
    dsl.execute(
        """
        INSERT INTO identity_contact_verification_challenge(
          challenge_id,contact_id,verifier,verifier_key_id,locale,state,failed_attempts,
          expires_at,last_sent_at,created_at,updated_at)
        VALUES (?,?,?,?,?,'ACTIVE',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        p.challengeId(),
        p.contactId(),
        p.verifier(),
        p.verifierKeyId(),
        p.locale().canonical(),
        ts(p.expiresAt()),
        ts(p.now()),
        ts(p.now()),
        ts(p.now()));
    dsl.execute(
        """
        INSERT INTO identity_notification_outbox(
          outbox_id,request_id,challenge_id,password_recovery_challenge_id,
          contact_verification_challenge_id,content_type,channel,locale,escrow_format_version,
          escrow_key_id,payload_nonce,payload_ciphertext,message_not_after,sensitive_expires_at,
          state,attempt_count,next_attempt_at,created_at,updated_at)
        VALUES (?,?,NULL,NULL,?,'CONTACT_VERIFICATION',?,?,1,?,?,?,
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        p.outboxId(),
        p.notificationRequestId(),
        p.challengeId(),
        p.contact().channel()
                == com.sajtech.identity.domain.registration.valueobject.RegistrationChannel.EMAIL
            ? "EMAIL"
            : "SMS",
        p.locale().canonical(),
        p.handoff().keyId(),
        p.handoff().nonce(),
        p.handoff().ciphertext(),
        ts(p.expiresAt()),
        ts(p.expiresAt()),
        ts(p.now()),
        ts(p.now()),
        ts(p.now()));
  }

  private static LockedContactChallenge mapChallenge(Record r) {
    return new LockedContactChallenge(
        r.get("challenge_id", UUID.class),
        r.get("contact_id", UUID.class),
        r.get("user_id", UUID.class),
        r.get("verifier", byte[].class),
        r.get("verifier_key_id", String.class),
        r.get("state", String.class),
        r.get("failed_attempts", Integer.class),
        r.get("expires_at", OffsetDateTime.class).toInstant(),
        r.get("last_sent_at", OffsetDateTime.class).toInstant(),
        r.get("contact_type", String.class),
        r.get("delivery_value", String.class),
        r.get("locale", String.class));
  }

  private static ProfileCommandRecord mapCommand(Record r) {
    return new ProfileCommandRecord(
        r.get("request_id", UUID.class),
        r.get("user_id", UUID.class),
        r.get("operation", String.class),
        r.get("intent_fingerprint", byte[].class),
        r.get("fingerprint_version", String.class),
        r.get("fingerprint_key_id", String.class),
        r.get("outcome", String.class),
        r.get("result_id", UUID.class),
        r.get("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime ts(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
