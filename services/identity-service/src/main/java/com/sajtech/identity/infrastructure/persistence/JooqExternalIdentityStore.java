package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.externalidentity.model.EncryptedExternalIdentityResult;
import com.sajtech.identity.application.externalidentity.port.out.ExternalIdentityStore;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

public final class JooqExternalIdentityStore implements ExternalIdentityStore {
  private final DSLContext dsl;

  public JooqExternalIdentityStore(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public void lockEvidence(byte[] evidenceId) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(encode(?::bytea, 'hex'), 0))", evidenceId);
  }

  @Override
  public void lockRequest(UUID requestId, String operation) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || E'\\000' || ?, 0))",
        requestId.toString(),
        operation);
  }

  @Override
  public Optional<ConsumedEvidence> findEvidence(byte[] evidenceId) {
    return dsl.fetchOptional(
            """
            SELECT evidence_id,request_id,operation,issuer,subject,evidence_fingerprint,fingerprint_key_id,
                   fingerprint_version,outcome,result_user_id,result_key_id,result_nonce,result_ciphertext
            FROM identity_oidc_evidence WHERE evidence_id=? FOR UPDATE
            """,
            evidenceId)
        .map(JooqExternalIdentityStore::evidence);
  }

  @Override
  public Optional<ConsumedEvidence> findEvidenceByRequest(UUID requestId, String operation) {
    return dsl.fetchOptional(
            """
            SELECT evidence_id,request_id,operation,issuer,subject,evidence_fingerprint,
                   fingerprint_key_id,fingerprint_version,outcome,result_user_id,result_key_id,
                   result_nonce,result_ciphertext
            FROM identity_oidc_evidence WHERE request_id=? AND operation=? FOR UPDATE
            """,
            requestId,
            operation)
        .map(JooqExternalIdentityStore::evidence);
  }

  @Override
  public void lockSubject(String issuer, String subject) {
    dsl.fetchOne(
        "SELECT pg_advisory_xact_lock(hashtextextended(? || E'\\000' || ?, 0))", issuer, subject);
  }

  @Override
  public void lockContactKey(String canonicalEmail) {
    dsl.fetchOne("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "EMAIL:" + canonicalEmail);
  }

  @Override
  public Optional<Binding> findActiveBinding(String issuer, String subject) {
    return dsl.fetchOptional(
            """
            SELECT e.external_identity_id,e.user_id,u.status
            FROM identity_external_identity e
            JOIN identity_user u ON u.user_id=e.user_id
            WHERE e.issuer=? AND e.subject=? AND e.unlinked_at IS NULL
            """,
            issuer,
            subject)
        .map(JooqExternalIdentityStore::binding);
  }

  @Override
  public Optional<Binding> findActiveBinding(UUID userId, String issuer) {
    return dsl.fetchOptional(
            """
            SELECT e.external_identity_id,e.user_id,u.status
            FROM identity_external_identity e
            JOIN identity_user u ON u.user_id=e.user_id
            WHERE e.user_id=? AND e.issuer=? AND e.unlinked_at IS NULL
            FOR UPDATE OF e,u
            """,
            userId,
            issuer)
        .map(JooqExternalIdentityStore::binding);
  }

  @Override
  public boolean verifiedEmailUnavailable(String canonicalEmail, UUID allowedUserId) {
    return dsl.fetchOptional(
            """
        SELECT 1 FROM identity_contact
        WHERE contact_type='EMAIL' AND canonical_value=? AND verified_at IS NOT NULL
          AND removed_at IS NULL AND (?::uuid IS NULL OR user_id<>?::uuid)
        UNION ALL
        SELECT 1 FROM registration_reservation
        WHERE contact_type='EMAIL' AND canonical_value=? AND expires_at>CURRENT_TIMESTAMP
          AND (?::uuid IS NULL OR user_id<>?::uuid)
        LIMIT 1
        """,
            canonicalEmail,
            allowedUserId,
            allowedUserId,
            canonicalEmail,
            allowedUserId,
            allowedUserId)
        .isPresent();
  }

  @Override
  public UUID createExternalUser(
      String issuer,
      String subject,
      String canonicalEmail,
      String deliveryEmail,
      String givenName,
      String familyName,
      Instant now) {
    UUID userId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        userId,
        ts(now),
        ts(now));
    dsl.execute(
        "INSERT INTO identity_profile(user_id,first_name,last_name,father_name,created_at,updated_at) VALUES (?,'','',NULL,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        userId,
        ts(now),
        ts(now));
    if (canonicalEmail != null) {
      dsl.execute(
          """
          INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,
            verified_at,primary_active,created_at,updated_at)
          VALUES (?,?,'EMAIL',?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),TRUE,
            CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
          """,
          UUID.randomUUID(),
          userId,
          canonicalEmail,
          deliveryEmail,
          ts(now),
          ts(now),
          ts(now));
    }
    link(UUID.randomUUID(), userId, issuer, subject, now);
    audit("IDENTITY_EXTERNAL_USER_CREATED", userId, now);
    return userId;
  }

  @Override
  public void link(
      UUID externalIdentityId, UUID userId, String issuer, String subject, Instant now) {
    int inserted =
        dsl.execute(
            """
            INSERT INTO identity_external_identity(
              external_identity_id,user_id,issuer,subject,linked_at,created_at,updated_at)
            VALUES (?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),
                    CAST(? AS TIMESTAMP WITH TIME ZONE))
            """,
            externalIdentityId,
            userId,
            issuer,
            subject,
            ts(now),
            ts(now),
            ts(now));
    if (inserted != 1) throw new IllegalStateException("External identity insert failed");
    audit("IDENTITY_EXTERNAL_IDENTITY_LINKED", userId, now);
  }

  @Override
  public void unlink(UUID externalIdentityId, Instant now) {
    Record row =
        dsl.fetchOne(
            "SELECT user_id FROM identity_external_identity WHERE external_identity_id=? AND unlinked_at IS NULL FOR UPDATE",
            externalIdentityId);
    if (row == null) return;
    UUID userId = row.get("user_id", UUID.class);
    int changed =
        dsl.execute(
            "UPDATE identity_external_identity SET unlinked_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE external_identity_id=? AND unlinked_at IS NULL",
            ts(now),
            ts(now),
            externalIdentityId);
    if (changed != 1) throw new IllegalStateException("External identity unlink failed");
    audit("IDENTITY_EXTERNAL_IDENTITY_UNLINKED", userId, now);
  }

  @Override
  public boolean hasLocalCredential(UUID userId) {
    return dsl.fetchOptional("SELECT 1 FROM identity_credential WHERE user_id=?", userId)
        .isPresent();
  }

  @Override
  public int activeExternalIdentityCount(UUID userId) {
    Record row =
        dsl.fetchOne(
            "SELECT count(*)::integer AS active_count FROM identity_external_identity WHERE user_id=? AND unlinked_at IS NULL",
            userId);
    Integer count = row == null ? null : row.get("active_count", Integer.class);
    return count == null ? -1 : count;
  }

  @Override
  public void saveEvidence(
      byte[] evidenceId,
      UUID requestId,
      String operation,
      String issuer,
      String subject,
      FingerprintDigest fingerprint,
      String outcome,
      UUID userId,
      EncryptedExternalIdentityResult encryptedResult,
      Instant issuedAt,
      Instant consumedAt,
      Instant retainUntil) {
    dsl.execute(
        """
        INSERT INTO identity_oidc_evidence(
          evidence_id,request_id,operation,workload_identity,issuer,subject,evidence_fingerprint,
          fingerprint_key_id,fingerprint_version,outcome,result_user_id,result_key_id,result_nonce,
          result_ciphertext,evidence_issued_at,consumed_at,retain_until)
        VALUES (?,?,?,'web-bff',?,?,?,?,?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        evidenceId,
        requestId,
        operation,
        issuer,
        subject,
        fingerprint.value(),
        fingerprint.keyId(),
        fingerprint.version(),
        outcome,
        userId,
        encryptedResult == null ? null : encryptedResult.keyId(),
        encryptedResult == null ? null : encryptedResult.nonce(),
        encryptedResult == null ? null : encryptedResult.ciphertext(),
        ts(issuedAt),
        ts(consumedAt),
        ts(retainUntil));
  }

  @Override
  public int deleteEvidenceBefore(Instant cutoff, int batch) {
    if (batch < 1 || batch > 256) throw new IllegalArgumentException("Evidence batch is invalid");
    return dsl.execute(
        """
        DELETE FROM identity_oidc_evidence e WHERE e.evidence_id IN (
          SELECT evidence_id FROM identity_oidc_evidence
          WHERE retain_until<CAST(? AS TIMESTAMP WITH TIME ZONE)
          ORDER BY retain_until,evidence_id LIMIT ? FOR UPDATE SKIP LOCKED)
        """,
        ts(cutoff),
        batch);
  }

  private void audit(String code, UUID userId, Instant now) {
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,occurred_at) VALUES (?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        code,
        userId,
        ts(now));
  }

  private static Binding binding(Record record) {
    return new Binding(
        record.get("external_identity_id", UUID.class),
        record.get("user_id", UUID.class),
        record.get("status", String.class));
  }

  private static ConsumedEvidence evidence(Record record) {
    String keyId = record.get("result_key_id", String.class);
    EncryptedExternalIdentityResult result =
        keyId == null
            ? null
            : new EncryptedExternalIdentityResult(
                keyId,
                record.get("result_nonce", byte[].class),
                record.get("result_ciphertext", byte[].class));
    return new ConsumedEvidence(
        record.get("evidence_id", byte[].class),
        record.get("request_id", UUID.class),
        record.get("operation", String.class),
        record.get("issuer", String.class),
        record.get("subject", String.class),
        record.get("evidence_fingerprint", byte[].class),
        record.get("fingerprint_key_id", String.class),
        record.get("fingerprint_version", String.class),
        record.get("outcome", String.class),
        record.get("result_user_id", UUID.class),
        result);
  }

  private static OffsetDateTime ts(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
