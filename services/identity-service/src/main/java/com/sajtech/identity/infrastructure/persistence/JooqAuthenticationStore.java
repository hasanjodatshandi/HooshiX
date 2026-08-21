package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqAuthenticationStore implements AuthenticationStore {
  private final DSLContext dsl;

  public JooqAuthenticationStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact) {
    return dsl.fetchOptional(
            """
            SELECT u.user_id, u.status, cr.password_hash
            FROM identity_contact c
            JOIN identity_user u ON u.user_id = c.user_id
            JOIN identity_credential cr ON cr.user_id = u.user_id
            WHERE c.contact_type = ?
              AND c.canonical_value = ?
              AND c.verified_at IS NOT NULL
              AND c.removed_at IS NULL
            LIMIT 1
            """,
            contact.channel().name(),
            contact.canonicalValue())
        .map(JooqAuthenticationStore::mapLocalCredential);
  }

  @Override
  public Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
      UUID userId, CanonicalContact contact) {
    return dsl.fetchOptional(
            """
            SELECT u.user_id, u.status, cr.password_hash
            FROM identity_contact c
            JOIN identity_user u ON u.user_id = c.user_id
            JOIN identity_credential cr ON cr.user_id = u.user_id
            WHERE u.user_id = ?
              AND c.contact_type = ?
              AND c.canonical_value = ?
              AND c.verified_at IS NOT NULL
              AND c.removed_at IS NULL
            FOR UPDATE OF c, u, cr
            """,
            userId,
            contact.channel().name(),
            contact.canonicalValue())
        .map(JooqAuthenticationStore::mapLocalCredential);
  }

  @Override
  public void expireDueFamilies(UUID userId, Instant now) {
    dsl.execute(
        """
        UPDATE identity_refresh_credential rc
        SET state = 'REVOKED', retired_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE rc.state = 'ACTIVE'
          AND rc.refresh_family_id IN (
            SELECT f.refresh_family_id
            FROM identity_refresh_family f
            WHERE f.user_id = ? AND f.state = 'ACTIVE'
              AND (f.idle_expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE)
                   OR f.absolute_expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE))
          )
        """,
        ts(now),
        userId,
        ts(now),
        ts(now));
    int changed =
        dsl.execute(
            """
            UPDATE identity_refresh_family
            SET state = 'REVOKED', revoked_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                revocation_reason = 'EXPIRED', updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE user_id = ? AND state = 'ACTIVE'
              AND (idle_expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE)
                   OR absolute_expires_at <= CAST(? AS TIMESTAMP WITH TIME ZONE))
            """,
            ts(now),
            ts(now),
            userId,
            ts(now),
            ts(now));
    if (changed > 0) audit("IDENTITY_SESSION_EXPIRED", userId, now);
  }

  @Override
  public int countActiveFamilies(UUID userId) {
    Record record =
        dsl.fetchOne(
            "SELECT count(*)::integer AS active_count FROM identity_refresh_family WHERE user_id = ? AND state = 'ACTIVE'",
            userId);
    if (record == null) return -1;
    Integer value = record.get("active_count", Integer.class);
    return value == null ? -1 : value;
  }

  @Override
  public Optional<UUID> oldestActiveFamily(UUID userId) {
    return dsl.fetchOptional(
            """
            SELECT refresh_family_id
            FROM identity_refresh_family
            WHERE user_id = ? AND state = 'ACTIVE'
            ORDER BY created_at, refresh_family_id
            LIMIT 1
            """,
            userId)
        .map(record -> record.get("refresh_family_id", UUID.class));
  }

  @Override
  public void createSession(PreparedSession session) {
    dsl.execute(
        """
        INSERT INTO identity_refresh_family(
          refresh_family_id,session_id,user_id,state,session_mode,selected_tenant_id,selected_membership_id,authentication_method,
          authenticated_at,created_at,last_activity_at,idle_expires_at,absolute_expires_at,updated_at)
        VALUES (?,? ,?,'ACTIVE',?,?,?,'LOCAL_PASSWORD',
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
          CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        session.refreshFamilyId(),
        session.sessionId(),
        session.userId(),
        session.mode().name(),
        session.selectedTenantId(),
        session.selectedMembershipId(),
        ts(session.authenticatedAt()),
        ts(session.createdAt()),
        ts(session.createdAt()),
        ts(session.idleExpiresAt()),
        ts(session.absoluteExpiresAt()),
        ts(session.createdAt()));
    insertCredential(
        session.credentialId(),
        session.refreshFamilyId(),
        session.refreshDigest(),
        session.createdAt());
    audit("IDENTITY_SESSION_CREATED", session.userId(), session.createdAt());
  }

  @Override
  public Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest) {
    Optional<UUID> candidateUser =
        dsl.fetchOptional(
                """
                SELECT f.user_id
                FROM identity_refresh_credential rc
                JOIN identity_refresh_family f ON f.refresh_family_id = rc.refresh_family_id
                WHERE rc.digest_key_id = ? AND rc.digest_version = ? AND rc.token_digest = ?
                """,
                digest.keyId(),
                digest.version(),
                digest.digest())
            .map(record -> record.get("user_id", UUID.class));
    if (candidateUser.isEmpty()) return Optional.empty();
    dsl.fetchOne(
        "SELECT user_id FROM identity_user WHERE user_id = ? FOR UPDATE", candidateUser.get());
    return dsl.fetchOptional(
            """
            SELECT rc.credential_id, rc.refresh_family_id, rc.state AS credential_state,
                   f.session_id, f.user_id, f.state AS family_state, f.session_mode,
                   f.selected_tenant_id, f.selected_membership_id,
                   f.idle_expires_at, f.absolute_expires_at, u.status AS user_status
            FROM identity_refresh_credential rc
            JOIN identity_refresh_family f ON f.refresh_family_id = rc.refresh_family_id
            JOIN identity_user u ON u.user_id = f.user_id
            WHERE rc.digest_key_id = ? AND rc.digest_version = ? AND rc.token_digest = ?
              AND f.user_id = ?
            FOR UPDATE OF rc, f
            """,
            digest.keyId(),
            digest.version(),
            digest.digest(),
            candidateUser.get())
        .map(JooqAuthenticationStore::mapLockedRefresh);
  }

  @Override
  public void rotateRefresh(
      LockedRefreshCredential current,
      UUID newCredentialId,
      RefreshDigest nextDigest,
      Instant now,
      Instant nextIdleExpiresAt) {
    int retired =
        dsl.execute(
            """
            UPDATE identity_refresh_credential
            SET state = 'ROTATED', retired_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE credential_id = ? AND refresh_family_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            current.credentialId(),
            current.refreshFamilyId());
    if (retired != 1) throw invalidState();
    insertCredential(newCredentialId, current.refreshFamilyId(), nextDigest, now);
    int updated =
        dsl.execute(
            """
            UPDATE identity_refresh_family
            SET last_activity_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                idle_expires_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE refresh_family_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            ts(nextIdleExpiresAt),
            ts(now),
            current.refreshFamilyId());
    if (updated != 1) throw invalidState();
    audit("IDENTITY_SESSION_REFRESHED", current.userId(), now);
  }

  @Override
  public void revokeFamily(
      UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now) {
    Record row =
        dsl.fetchOne(
            "SELECT user_id FROM identity_refresh_family WHERE refresh_family_id = ? FOR UPDATE",
            refreshFamilyId);
    if (row == null) return;
    UUID userId = row.get("user_id", UUID.class);
    int changed =
        dsl.execute(
            """
            UPDATE identity_refresh_family
            SET state = 'REVOKED', revoked_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                revocation_reason = ?, updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE refresh_family_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            reason.name(),
            ts(now),
            refreshFamilyId);
    if (changed == 0) return;
    dsl.execute(
        """
        UPDATE identity_refresh_credential
        SET state = 'REVOKED', retired_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE refresh_family_id = ? AND state = 'ACTIVE'
        """,
        ts(now),
        refreshFamilyId);
    audit(
        reason == RefreshFamilyRevocationReason.REFRESH_REUSE
            ? "IDENTITY_REFRESH_REUSE_DETECTED"
            : "IDENTITY_REFRESH_FAMILY_REVOKED",
        userId,
        now);
  }

  @Override
  public void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now) {
    dsl.execute(
        """
        UPDATE identity_refresh_credential rc
        SET state = 'REVOKED', retired_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
        WHERE rc.state = 'ACTIVE' AND rc.refresh_family_id IN (
          SELECT refresh_family_id FROM identity_refresh_family WHERE user_id = ? AND state = 'ACTIVE'
        )
        """,
        ts(now),
        userId);
    int changed =
        dsl.execute(
            """
            UPDATE identity_refresh_family
            SET state = 'REVOKED', revoked_at = CAST(? AS TIMESTAMP WITH TIME ZONE),
                revocation_reason = ?, updated_at = CAST(? AS TIMESTAMP WITH TIME ZONE)
            WHERE user_id = ? AND state = 'ACTIVE'
            """,
            ts(now),
            reason.name(),
            ts(now),
            userId);
    if (changed > 0) audit("IDENTITY_ALL_SESSIONS_REVOKED", userId, now);
  }

  @Override
  public int deleteFamiliesBefore(Instant cutoff, int batch) {
    if (batch <= 0 || batch > 256)
      throw new IllegalArgumentException("Session cleanup batch is invalid");
    return dsl.execute(
        """
        DELETE FROM identity_refresh_family f
        WHERE f.refresh_family_id IN (
          SELECT refresh_family_id
          FROM identity_refresh_family
          WHERE absolute_expires_at < CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY absolute_expires_at, refresh_family_id
          FOR UPDATE SKIP LOCKED
          LIMIT ?
        )
        """,
        ts(cutoff),
        batch);
  }

  private void insertCredential(
      UUID credentialId, UUID familyId, RefreshDigest digest, Instant issuedAt) {
    dsl.execute(
        """
        INSERT INTO identity_refresh_credential(
          credential_id,refresh_family_id,token_digest,digest_key_id,digest_version,state,issued_at)
        VALUES (?,?,?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        credentialId,
        familyId,
        digest.digest(),
        digest.keyId(),
        digest.version(),
        ts(issuedAt));
  }

  private void audit(String code, UUID userId, Instant now) {
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,contact_id,occurred_at) VALUES (?,?,?,NULL,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        code,
        userId,
        ts(now));
  }

  private static LocalCredentialRecord mapLocalCredential(Record record) {
    return new LocalCredentialRecord(
        record.get("user_id", UUID.class),
        record.get("status", String.class),
        record.get("password_hash", String.class));
  }

  private static LockedRefreshCredential mapLockedRefresh(Record record) {
    return new LockedRefreshCredential(
        record.get("credential_id", UUID.class),
        record.get("refresh_family_id", UUID.class),
        record.get("session_id", String.class),
        record.get("user_id", UUID.class),
        record.get("credential_state", String.class),
        record.get("family_state", String.class),
        record.get("user_status", String.class),
        AuthenticationSessionMode.valueOf(record.get("session_mode", String.class)),
        record.get("selected_tenant_id", UUID.class),
        record.get("selected_membership_id", UUID.class),
        record.get("idle_expires_at", OffsetDateTime.class).toInstant(),
        record.get("absolute_expires_at", OffsetDateTime.class).toInstant());
  }

  private static AuthenticationException invalidState() {
    return new AuthenticationException(
        AuthenticationError.SESSION_STATE_INVALID, "Session family state is invalid");
  }

  private static OffsetDateTime ts(Instant value) {
    return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }
}
