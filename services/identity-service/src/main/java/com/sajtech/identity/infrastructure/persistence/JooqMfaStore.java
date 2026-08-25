package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.out.MfaStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqMfaStore implements MfaStore {
  private final DSLContext dsl;

  public JooqMfaStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean requiresMfa(UUID userId) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("identity_totp_enrollment")
            .where("user_id = ? AND state = 'ACTIVE'", userId));
  }

  @Override
  public MfaStatus status(UUID userId) {
    Record record =
        dsl.fetchOne(
            """
            SELECT EXISTS(
                     SELECT 1 FROM identity_totp_enrollment
                     WHERE user_id = ? AND state = 'ACTIVE'
                   ) AS enabled,
                   (SELECT count(*)::integer FROM identity_mfa_recovery_code
                    WHERE user_id = ? AND state = 'ACTIVE') AS remaining
            """,
            userId,
            userId);
    if (record == null) throw stateConflict();
    return new MfaStatus(
        Boolean.TRUE.equals(record.get("enabled", Boolean.class)),
        record.get("remaining", Integer.class));
  }

  @Override
  public Optional<ActiveEnrollment> lockActiveEnrollment(UUID userId) {
    return dsl.fetchOptional(
            """
            SELECT enrollment_id,user_id,secret_key_id,secret_nonce,secret_ciphertext,
                   last_accepted_timestep
            FROM identity_totp_enrollment
            WHERE user_id = ? AND state = 'ACTIVE'
            FOR UPDATE
            """,
            userId)
        .map(JooqMfaStore::activeEnrollment);
  }

  @Override
  public void replacePendingEnrollment(PreparedPendingEnrollment pending, Instant now) {
    dsl.execute(
        """
        UPDATE identity_totp_pending_enrollment
        SET state = 'REPLACED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE user_id = ? AND state = 'ACTIVE'
        """,
        ts(now),
        pending.userId());
    int inserted =
        dsl.execute(
            """
            INSERT INTO identity_totp_pending_enrollment(
              pending_enrollment_id,user_id,replaces_enrollment_id,
              challenge_digest,digest_key_id,digest_version,
              secret_key_id,secret_version,secret_nonce,secret_ciphertext,
              state,failed_attempts,current_proof_verified_at,expires_at,created_at,updated_at)
            VALUES (?,?,?,?,?,'mfa-challenge-hmac-v1',?,'mfa-aes-gcm-v1',?,?,'ACTIVE',0,?,
                    CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE))
            """,
            pending.pendingEnrollmentId(),
            pending.userId(),
            pending.replacesEnrollmentId(),
            pending.challenge().digest().digest(),
            pending.challenge().digest().keyId(),
            pending.secret().keyId(),
            pending.secret().nonce(),
            pending.secret().ciphertext(),
            pending.currentProofVerifiedAt() == null ? null : ts(pending.currentProofVerifiedAt()),
            ts(pending.expiresAt()),
            ts(pending.createdAt()),
            ts(pending.createdAt()));
    if (inserted != 1) throw stateConflict();
    audit("IDENTITY_MFA_ENROLLMENT_STARTED", pending.userId(), now);
  }

  @Override
  public Optional<PendingEnrollment> lockPendingEnrollment(List<MfaDigest> challengeDigests) {
    return locateAndLockPending(challengeDigests);
  }

  @Override
  public void recordPendingFailure(UUID pendingEnrollmentId, int failedAttempts, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_totp_pending_enrollment
            SET failed_attempts = ?, state = CASE WHEN ? >= 5 THEN 'EXHAUSTED' ELSE state END,
                updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE pending_enrollment_id = ? AND state = 'ACTIVE'
            """,
            failedAttempts,
            failedAttempts,
            ts(now),
            pendingEnrollmentId);
    if (changed != 1) throw stateConflict();
  }

  @Override
  public void confirmEnrollment(
      PendingEnrollment pending,
      UUID enrollmentId,
      long acceptedTimestep,
      List<GeneratedRecoveryCode> recoveryCodes,
      Instant now) {
    if (!pending.pendingEnrollmentId().equals(enrollmentId) || recoveryCodes.size() != 10) {
      throw stateConflict();
    }
    if (pending.replacesEnrollmentId() != null) {
      int replaced =
          dsl.execute(
              """
              UPDATE identity_totp_enrollment
              SET state = 'REPLACED', ended_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                  updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
              WHERE enrollment_id = ? AND user_id = ? AND state = 'ACTIVE'
              """,
              ts(now),
              ts(now),
              pending.replacesEnrollmentId(),
              pending.userId());
      if (replaced != 1) throw stateConflict();
      dsl.execute(
          """
          UPDATE identity_mfa_recovery_code
          SET state = 'REVOKED', consumed_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
          WHERE enrollment_id = ? AND state = 'ACTIVE'
          """,
          ts(now),
          pending.replacesEnrollmentId());
    }
    int inserted =
        dsl.execute(
            """
            INSERT INTO identity_totp_enrollment(
              enrollment_id,user_id,state,secret_key_id,secret_version,secret_nonce,
              secret_ciphertext,last_accepted_timestep,activated_at,created_at,updated_at)
            VALUES (?,?,'ACTIVE',?,'mfa-aes-gcm-v1',?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
            """,
            enrollmentId,
            pending.userId(),
            pending.secret().keyId(),
            pending.secret().nonce(),
            pending.secret().ciphertext(),
            acceptedTimestep,
            ts(now),
            ts(now),
            ts(now));
    if (inserted != 1) throw stateConflict();
    insertRecoveryCodes(pending.userId(), enrollmentId, recoveryCodes, now);
    int confirmed =
        dsl.execute(
            """
            UPDATE identity_totp_pending_enrollment
            SET state = 'CONFIRMED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE pending_enrollment_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            pending.pendingEnrollmentId());
    if (confirmed != 1) throw stateConflict();
    audit("IDENTITY_MFA_TOTP_ENABLED", pending.userId(), now);
  }

  @Override
  public void replaceLoginChallenge(
      UUID challengeId,
      UUID userId,
      GeneratedMfaChallenge challenge,
      Instant now,
      Instant expiresAt) {
    dsl.execute(
        """
        UPDATE identity_mfa_login_challenge
        SET state = 'SUPERSEDED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE user_id = ? AND state = 'ACTIVE'
        """,
        ts(now),
        userId);
    int inserted =
        dsl.execute(
            """
            INSERT INTO identity_mfa_login_challenge(
              challenge_id,user_id,locator_digest,digest_key_id,digest_version,
              authentication_method,state,failed_attempts,primary_authenticated_at,
              expires_at,created_at,updated_at)
            VALUES (?,?,?,?,?,'LOCAL_PASSWORD','ACTIVE',0,CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE))
            """,
            challengeId,
            userId,
            challenge.digest().digest(),
            challenge.digest().keyId(),
            challenge.digest().version(),
            ts(now),
            ts(expiresAt),
            ts(now),
            ts(now));
    if (inserted != 1) throw stateConflict();
    audit("IDENTITY_MFA_LOGIN_CHALLENGE_CREATED", userId, now);
  }

  @Override
  public Optional<LoginChallenge> lockLoginChallenge(List<MfaDigest> challengeDigests) {
    for (MfaDigest digest : challengeDigests) {
      Optional<Record> candidate =
          dsl.fetchOptional(
              """
              SELECT challenge_id,user_id
              FROM identity_mfa_login_challenge
              WHERE digest_key_id = ? AND digest_version = ? AND locator_digest = ?
              """,
              digest.keyId(),
              digest.version(),
              digest.digest());
      if (candidate.isEmpty()) continue;
      UUID userId = candidate.get().get("user_id", UUID.class);
      dsl.fetchOne("SELECT user_id FROM identity_user WHERE user_id = ? FOR UPDATE", userId);
      return dsl.fetchOptional(
              """
              SELECT challenge_id,user_id,failed_attempts,primary_authenticated_at,expires_at,state
              FROM identity_mfa_login_challenge
              WHERE challenge_id = ? AND digest_key_id = ? AND digest_version = ?
                AND locator_digest = ?
              FOR UPDATE
              """,
              candidate.get().get("challenge_id", UUID.class),
              digest.keyId(),
              digest.version(),
              digest.digest())
          .map(JooqMfaStore::loginChallenge);
    }
    return Optional.empty();
  }

  @Override
  public Optional<LoginChallenge> findLoginChallenge(List<MfaDigest> challengeDigests) {
    for (MfaDigest digest : challengeDigests) {
      Optional<LoginChallenge> found =
          dsl.fetchOptional(
                  """
                  SELECT challenge_id,user_id,failed_attempts,primary_authenticated_at,expires_at,state
                  FROM identity_mfa_login_challenge
                  WHERE digest_key_id = ? AND digest_version = ? AND locator_digest = ?
                  """,
                  digest.keyId(),
                  digest.version(),
                  digest.digest())
              .map(JooqMfaStore::loginChallenge);
      if (found.isPresent()) return found;
    }
    return Optional.empty();
  }

  @Override
  public void recordLoginFailure(UUID challengeId, int failedAttempts, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_mfa_login_challenge
            SET failed_attempts = ?, state = CASE WHEN ? >= 5 THEN 'EXHAUSTED' ELSE state END,
                updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE challenge_id = ? AND state = 'ACTIVE'
            """,
            failedAttempts,
            failedAttempts,
            ts(now),
            challengeId);
    if (changed != 1) throw stateConflict();
  }

  @Override
  public void completeLoginChallenge(UUID challengeId, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_mfa_login_challenge
            SET state = 'USED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE challenge_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            challengeId);
    if (changed != 1) throw stateConflict();
  }

  @Override
  public void acceptTotp(UUID enrollmentId, long acceptedTimestep, Instant now) {
    int changed =
        dsl.execute(
            """
            UPDATE identity_totp_enrollment
            SET last_accepted_timestep = ?, updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE enrollment_id = ? AND state = 'ACTIVE'
              AND (last_accepted_timestep IS NULL OR last_accepted_timestep < ?)
            """,
            acceptedTimestep,
            ts(now),
            enrollmentId,
            acceptedTimestep);
    if (changed != 1) {
      throw new MfaException(MfaError.REPLAYED_PROOF, "MFA proof has already been used");
    }
  }

  @Override
  public boolean consumeRecoveryCode(
      UUID userId, UUID enrollmentId, List<MfaDigest> digestCandidates, Instant now) {
    for (MfaDigest digest : digestCandidates) {
      int changed =
          dsl.execute(
              """
              UPDATE identity_mfa_recovery_code
              SET state = 'USED', consumed_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
              WHERE user_id = ? AND enrollment_id = ? AND state = 'ACTIVE'
                AND digest_key_id = ? AND digest_version = ? AND code_digest = ?
              """,
              ts(now),
              userId,
              enrollmentId,
              digest.keyId(),
              digest.version(),
              digest.digest());
      if (changed == 1) return true;
    }
    return false;
  }

  @Override
  public void disableEnrollment(UUID enrollmentId, Instant now) {
    Record row =
        dsl.fetchOne(
            "SELECT user_id FROM identity_totp_enrollment WHERE enrollment_id = ? FOR UPDATE",
            enrollmentId);
    if (row == null) throw stateConflict();
    int changed =
        dsl.execute(
            """
            UPDATE identity_totp_enrollment
            SET state = 'DISABLED', ended_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE enrollment_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            ts(now),
            enrollmentId);
    if (changed != 1) throw stateConflict();
    dsl.execute(
        """
        UPDATE identity_mfa_recovery_code
        SET state = 'REVOKED', consumed_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE enrollment_id = ? AND state = 'ACTIVE'
        """,
        ts(now),
        enrollmentId);
    audit("IDENTITY_MFA_TOTP_DISABLED", row.get("user_id", UUID.class), now);
  }

  @Override
  public void replaceRecoveryCodes(
      UUID userId, UUID enrollmentId, List<GeneratedRecoveryCode> recoveryCodes, Instant now) {
    if (recoveryCodes.size() != 10) throw stateConflict();
    dsl.execute(
        """
        UPDATE identity_mfa_recovery_code
        SET state = 'REVOKED', consumed_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE user_id = ? AND enrollment_id = ? AND state = 'ACTIVE'
        """,
        ts(now),
        userId,
        enrollmentId);
    insertRecoveryCodes(userId, enrollmentId, recoveryCodes, now);
    audit("IDENTITY_MFA_RECOVERY_CODES_ROTATED", userId, now);
  }

  @Override
  public int deletePendingEnrollmentsBefore(Instant cutoff, int batch) {
    validateCleanupBatch(batch);
    return dsl.execute(
        """
        DELETE FROM identity_totp_pending_enrollment p
        WHERE p.pending_enrollment_id IN (
          SELECT pending_enrollment_id
          FROM identity_totp_pending_enrollment
          WHERE expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY expires_at, pending_enrollment_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        """,
        ts(cutoff),
        batch);
  }

  @Override
  public int deleteLoginChallengesBefore(Instant cutoff, int batch) {
    validateCleanupBatch(batch);
    return dsl.execute(
        """
        DELETE FROM identity_mfa_login_challenge c
        WHERE c.challenge_id IN (
          SELECT challenge_id
          FROM identity_mfa_login_challenge
          WHERE expires_at < CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY expires_at, challenge_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        """,
        ts(cutoff),
        batch);
  }

  private Optional<PendingEnrollment> locateAndLockPending(List<MfaDigest> digests) {
    for (MfaDigest digest : digests) {
      Optional<Record> candidate =
          dsl.fetchOptional(
              """
              SELECT pending_enrollment_id,user_id
              FROM identity_totp_pending_enrollment
              WHERE digest_key_id = ? AND digest_version = ? AND challenge_digest = ?
              """,
              digest.keyId(),
              digest.version(),
              digest.digest());
      if (candidate.isEmpty()) continue;
      UUID userId = candidate.get().get("user_id", UUID.class);
      dsl.fetchOne("SELECT user_id FROM identity_user WHERE user_id = ? FOR UPDATE", userId);
      return dsl.fetchOptional(
              """
              SELECT pending_enrollment_id,user_id,replaces_enrollment_id,secret_key_id,
                     secret_nonce,secret_ciphertext,failed_attempts,current_proof_verified_at,
                     expires_at,state
              FROM identity_totp_pending_enrollment
              WHERE pending_enrollment_id = ? AND digest_key_id = ? AND digest_version = ?
                AND challenge_digest = ?
              FOR UPDATE
              """,
              candidate.get().get("pending_enrollment_id", UUID.class),
              digest.keyId(),
              digest.version(),
              digest.digest())
          .map(JooqMfaStore::pendingEnrollment);
    }
    return Optional.empty();
  }

  private void insertRecoveryCodes(
      UUID userId, UUID enrollmentId, List<GeneratedRecoveryCode> recoveryCodes, Instant now) {
    for (GeneratedRecoveryCode code : recoveryCodes) {
      dsl.execute(
          """
          INSERT INTO identity_mfa_recovery_code(
            recovery_code_id,enrollment_id,user_id,code_digest,digest_key_id,digest_version,
            state,created_at)
          VALUES (?,?,?,?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE))
          """,
          UUID.randomUUID(),
          enrollmentId,
          userId,
          code.digest().digest(),
          code.digest().keyId(),
          code.digest().version(),
          ts(now));
    }
  }

  private void audit(String eventCode, UUID userId, Instant now) {
    dsl.execute(
        """
        INSERT INTO identity_security_audit(event_id,event_code,user_id,contact_id,occurred_at)
        VALUES (?,?,?,NULL,CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        UUID.randomUUID(),
        eventCode,
        userId,
        ts(now));
  }

  private static ActiveEnrollment activeEnrollment(Record record) {
    return new ActiveEnrollment(
        record.get("enrollment_id", UUID.class),
        record.get("user_id", UUID.class),
        encrypted(record),
        record.get("last_accepted_timestep", Long.class));
  }

  private static PendingEnrollment pendingEnrollment(Record record) {
    OffsetDateTime proof = record.get("current_proof_verified_at", OffsetDateTime.class);
    return new PendingEnrollment(
        record.get("pending_enrollment_id", UUID.class),
        record.get("user_id", UUID.class),
        record.get("replaces_enrollment_id", UUID.class),
        encrypted(record),
        record.get("failed_attempts", Integer.class),
        proof == null ? null : proof.toInstant(),
        record.get("expires_at", OffsetDateTime.class).toInstant(),
        record.get("state", String.class));
  }

  private static LoginChallenge loginChallenge(Record record) {
    return new LoginChallenge(
        record.get("challenge_id", UUID.class),
        record.get("user_id", UUID.class),
        record.get("failed_attempts", Integer.class),
        record.get("primary_authenticated_at", OffsetDateTime.class).toInstant(),
        record.get("expires_at", OffsetDateTime.class).toInstant(),
        record.get("state", String.class));
  }

  private static EncryptedTotpSecret encrypted(Record record) {
    return new EncryptedTotpSecret(
        record.get("secret_key_id", String.class),
        record.get("secret_nonce", byte[].class),
        record.get("secret_ciphertext", byte[].class));
  }

  private static MfaException stateConflict() {
    return new MfaException(MfaError.STATE_CONFLICT, "MFA state is inconsistent");
  }

  private static void validateCleanupBatch(int batch) {
    if (batch <= 0 || batch > 256) {
      throw new IllegalArgumentException("MFA cleanup batch is invalid");
    }
  }

  private static OffsetDateTime ts(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
