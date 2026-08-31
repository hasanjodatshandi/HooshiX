package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.*;
import java.util.*;
import org.jooq.*;

abstract class TenantPersistenceSupport {
  protected final DSLContext dsl;
  protected final IntentFingerprintPort fingerprints;

  TenantPersistenceSupport(DSLContext dsl, IntentFingerprintPort fingerprints) {
    this.dsl = dsl;
    this.fingerprints = fingerprints;
  }

  protected TenantLifecycleMutation lifecycleResult(
      UUID requestId, UUID tenantId, String requestedTarget) {
    String lifecycle = string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenantId);
    if (lifecycle == null) throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not found");
    Boolean pending =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_authorization_outbox WHERE tenant_id=? AND operation='APPLY_TENANT_LIFECYCLE' AND state IN ('PENDING','DISPATCHING'))",
            tenantId);
    String responseTarget = "DELETING".equals(requestedTarget) ? "DELETED" : requestedTarget;
    return new TenantLifecycleMutation(
        tenantId, lifecycle, responseTarget, Boolean.TRUE.equals(pending));
  }

  protected void ensureNoPendingLifecycle(UUID tenantId) {
    Boolean pending =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_authorization_outbox WHERE tenant_id=? AND operation='APPLY_TENANT_LIFECYCLE' AND state IN ('PENDING','DISPATCHING'))",
            tenantId);
    if (Boolean.TRUE.equals(pending))
      throw error(TenantError.TENANT_LIFECYCLE_PENDING, "Tenant lifecycle command is pending");
  }

  protected List<InvitationSummary> invitationSummaries(Result<? extends org.jooq.Record> rows) {
    if (rows.size() > 200)
      throw error(TenantError.SESSION_STATE_INVALID, "Invitation result limit exceeded");
    List<InvitationSummary> result = new ArrayList<>();
    for (org.jooq.Record row : rows)
      result.add(
          new InvitationSummary(
              row.get("invitation_id", UUID.class),
              row.get("tenant_id", UUID.class),
              row.get("name", String.class),
              row.get("slug", String.class),
              row.get("state", String.class),
              row.get("expires_at", OffsetDateTime.class).toInstant()));
    return List.copyOf(result);
  }

  protected String effectiveInvitationState(
      UUID tenantId, UUID invitationId, org.jooq.Record invitation, Instant now) {
    String state = invitation.get("state", String.class);
    OffsetDateTime expiresAt = invitation.get("expires_at", OffsetDateTime.class);
    if ("PENDING".equals(state) && !now.isBefore(expiresAt.toInstant())) {
      updateInvitationState(tenantId, invitationId, "EXPIRED", now);
      return "EXPIRED";
    }
    return state;
  }

  protected void updateInvitationState(
      UUID tenantId, UUID invitationId, String state, Instant now) {
    dsl.execute(
        "UPDATE identity_tenant_invitation SET state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND invitation_id=? AND state='PENDING'",
        state,
        ts(now),
        tenantId,
        invitationId);
    dsl.execute(
        "UPDATE identity_invitation_query SET state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE invitation_id=? AND state='PENDING'",
        state,
        ts(now),
        invitationId);
  }

  protected Replay replay(UUID requestId, String operation, byte[] material) {
    var r =
        dsl.fetchOne(
            "SELECT result_id,intent_fingerprint,fingerprint_key_id,fingerprint_version,created_at FROM identity_tenant_command_dedup WHERE request_id=? AND operation=?",
            requestId,
            operation);
    if (r == null) return new Replay(false, null);
    CommandDedupRecord stored =
        new CommandDedupRecord(
            requestId,
            operation,
            r.get("intent_fingerprint", byte[].class),
            r.get("fingerprint_version", String.class),
            r.get("fingerprint_key_id", String.class),
            "ACCEPTED",
            r.get("created_at", OffsetDateTime.class).toInstant());
    if (!fingerprints.matches(material, stored))
      throw error(TenantError.REQUEST_ID_CONFLICT, "Request ID conflicts with a different intent");
    return new Replay(true, r.get("result_id", UUID.class));
  }

  protected void dedup(
      UUID requestId,
      String operation,
      UUID userId,
      UUID resultId,
      FingerprintDigest fp,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_tenant_command_dedup(request_id,operation,user_id,tenant_id,result_id,intent_fingerprint,fingerprint_key_id,fingerprint_version,created_at) VALUES (?,?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        requestId,
        operation,
        userId,
        "CREATE_TENANT".equals(operation) ? resultId : null,
        resultId,
        fp.value(),
        fp.keyId(),
        fp.version(),
        ts(now));
  }

  protected void outbox(
      UUID requestId,
      String operation,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      String lifecycle,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_authorization_outbox(outbox_id,request_id,operation,tenant_id,membership_id,user_id,lifecycle,state,attempt_count,next_attempt_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT(request_id,operation) DO NOTHING",
        UUID.randomUUID(),
        requestId,
        operation,
        tenantId,
        membershipId,
        userId,
        lifecycle,
        ts(now),
        ts(now),
        ts(now));
  }

  protected UUID membershipFor(UUID tenantId, UUID userId) {
    UUID id =
        uuid(
            "SELECT membership_id FROM identity_user_membership_query WHERE tenant_id=? AND user_id=?",
            tenantId,
            userId);
    if (id == null)
      throw error(TenantError.SESSION_STATE_INVALID, "Tenant replay result is incomplete");
    return id;
  }

  protected void upsertMembershipQuery(
      UUID user,
      UUID membership,
      UUID tenant,
      String memberState,
      String tenantState,
      String name,
      String slug,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_user_membership_query(user_id,membership_id,tenant_id,membership_lifecycle,tenant_lifecycle,tenant_name,tenant_slug,updated_at) VALUES (?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT(membership_id) DO UPDATE SET membership_lifecycle=EXCLUDED.membership_lifecycle,tenant_lifecycle=EXCLUDED.tenant_lifecycle,tenant_name=EXCLUDED.tenant_name,tenant_slug=EXCLUDED.tenant_slug,updated_at=EXCLUDED.updated_at",
        user,
        membership,
        tenant,
        memberState,
        tenantState,
        name,
        slug,
        ts(now));
  }

  protected void audit(String code, UUID user, Instant now) {
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,contact_id,occurred_at) VALUES (?,?,?,NULL,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        code,
        user,
        ts(now));
  }

  protected void setTenant(UUID tenant) {
    dsl.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", tenant.toString());
  }

  protected String string(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : v.toString();
  }

  protected UUID uuid(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : (UUID) v;
  }

  protected Boolean bool(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : (Boolean) v;
  }

  protected Instant instant(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : ((OffsetDateTime) v).toInstant();
  }

  protected static boolean isUnique(org.jooq.exception.DataAccessException e) {
    return "23505".equals(e.sqlState());
  }

  protected static OffsetDateTime ts(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  protected static TenantException error(TenantError e, String m) {
    return new TenantException(e, m);
  }

  protected record Replay(boolean present, UUID resultId) {}
}
