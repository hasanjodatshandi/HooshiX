package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

abstract class AuthorizationPersistenceSupport {
  static final String CHECK_PERMISSION_SQL =
      """
      SELECT CASE
        WHEN t.lifecycle <> 'ACTIVE' OR m.lifecycle <> 'ACTIVE' OR p.scope <> 'TENANT' OR p.lifecycle = 'RETIRED' THEN FALSE
        WHEN o.decision = 'DENY' THEN FALSE
        WHEN o.decision = 'GRANT' THEN TRUE
        WHEN EXISTS (
          SELECT 1 FROM authorization_membership_role mr
          JOIN authorization_role r ON r.tenant_id=mr.tenant_id AND r.role_id=mr.role_id AND r.lifecycle='ACTIVE'
          JOIN authorization_role_permission rp ON rp.tenant_id=r.tenant_id AND rp.role_id=r.role_id
          WHERE mr.tenant_id=? AND mr.membership_id=? AND rp.permission_key=?
        ) THEN TRUE ELSE FALSE END
      FROM authorization_permission_definition p
      JOIN authorization_tenant_projection t ON t.tenant_id=?
      JOIN authorization_membership_projection m ON m.tenant_id=t.tenant_id AND m.membership_id=?
      LEFT JOIN authorization_membership_permission_override o ON o.tenant_id=t.tenant_id AND o.membership_id=m.membership_id AND o.permission_key=p.permission_key
      WHERE p.permission_key=?
      """;
  protected static final List<String> TENANT_PERMISSIONS =
      List.of(
          "membership.owner.assign",
          "membership.permission.manage",
          "membership.read",
          "membership.role.assign",
          "role.archive",
          "role.create",
          "role.permission.manage",
          "role.read",
          "role.update",
          "tenant.delete",
          "tenant.read");
  protected static final List<String> MEMBER_PERMISSIONS =
      List.of("membership.read", "role.read", "tenant.read");
  protected final DSLContext dsl;
  protected final AuthorizationSecurityTelemetry securityTelemetry;

  AuthorizationPersistenceSupport(
      DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    this.dsl = dsl;
    this.securityTelemetry = securityTelemetry;
  }

  static void configureCheckPermissionTransaction(DSLContext tx, UUID tenantId) {
    setTenant(tx, tenantId);
    tx.fetchValue("SELECT set_config('statement_timeout', '100ms', true)");
  }

  protected boolean check(DSLContext tx, UUID tenantId, UUID membershipId, String key) {
    configureCheckPermissionTransaction(tx, tenantId);
    Boolean result =
        boolValue(
            tx, CHECK_PERMISSION_SQL, tenantId, membershipId, key, tenantId, membershipId, key);
    return Boolean.TRUE.equals(result);
  }

  protected static void setTenant(DSLContext tx, UUID tenantId) {
    tx.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString());
  }

  protected void requirePermission(DSLContext tx, ActorContext actor, String permission) {
    if (!check(tx, actor.tenantId(), actor.membershipId(), permission))
      throw error(AuthorizationError.AUTHORIZATION_DENIED, "Authorization denied");
  }

  protected static void lockTenantManagement(DSLContext tx, UUID tenantId) {
    var row =
        tx.fetchOne(
            "SELECT tenant_id FROM authorization_tenant_projection WHERE tenant_id=? FOR UPDATE",
            tenantId);
    UUID locked = row == null ? null : row.get("tenant_id", UUID.class);
    if (locked == null)
      throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Tenant is unknown");
  }

  protected static void lockOwnerGuard(DSLContext tx, UUID tenantId) {
    Integer guard =
        intValue(
            tx,
            "SELECT 1 FROM authorization_owner_safety_guard WHERE tenant_id=? FOR UPDATE",
            tenantId);
    if (guard == null)
      throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Owner safety guard is missing");
  }

  protected RoleModel role(DSLContext tx, UUID tenantId, UUID roleId) {
    var row =
        tx.fetchOne(
            "SELECT role_id,name,description,kind,lifecycle,version FROM authorization_role WHERE tenant_id=? AND role_id=?",
            tenantId,
            roleId);
    if (row == null) throw error(AuthorizationError.ROLE_NOT_FOUND, "Role is not found");
    List<String> permissions =
        tx.fetch(
                "SELECT permission_key FROM authorization_role_permission WHERE tenant_id=? AND role_id=? ORDER BY permission_key",
                tenantId,
                roleId)
            .getValues("permission_key", String.class);
    return new RoleModel(
        row.get("role_id", UUID.class),
        row.get("name", String.class),
        row.get("description", String.class),
        row.get("kind", String.class),
        row.get("lifecycle", String.class),
        row.get("version", Long.class),
        permissions);
  }

  protected static RoleState lockRole(DSLContext tx, UUID tenantId, UUID roleId) {
    var row =
        tx.fetchOne(
            "SELECT kind,lifecycle,version,name_key FROM authorization_role WHERE tenant_id=? AND role_id=? FOR UPDATE",
            tenantId,
            roleId);
    if (row == null) throw error(AuthorizationError.ROLE_NOT_FOUND, "Role is not found");
    return new RoleState(
        row.get("kind", String.class),
        row.get("lifecycle", String.class),
        row.get("version", Long.class),
        row.get("name_key", String.class));
  }

  protected static void requireMutable(RoleState state, long expectedVersion) {
    if ("SYSTEM".equals(state.kind()))
      throw error(AuthorizationError.SYSTEM_ROLE_IMMUTABLE, "SYSTEM role is immutable");
    if (!"ACTIVE".equals(state.lifecycle()))
      throw error(AuthorizationError.ROLE_ARCHIVED, "Role is archived");
    if (state.version() != expectedVersion)
      throw error(AuthorizationError.STALE_ROLE_VERSION, "Role version is stale");
  }

  protected void requireGrantablePermissions(
      DSLContext tx, ActorContext actor, List<String> permissions, boolean requireActorAuthority) {
    if (permissions.size() > 200)
      throw error(AuthorizationError.LIMIT_EXCEEDED, "Permission limit reached");
    for (String permission : permissions) {
      requirePermissionDefinition(tx, permission, true);
      if (requireActorAuthority) requirePermission(tx, actor, permission);
    }
  }

  protected static void requirePermissionDefinition(
      DSLContext tx, String key, boolean newlyAssigned) {
    var row =
        tx.fetchOne(
            "SELECT scope,lifecycle FROM authorization_permission_definition WHERE permission_key=?",
            key);
    if (row == null || !"TENANT".equals(row.get("scope", String.class)))
      throw error(AuthorizationError.PERMISSION_UNKNOWN, "Permission is unknown");
    String lifecycle = row.get("lifecycle", String.class);
    if ("RETIRED".equals(lifecycle))
      throw error(AuthorizationError.PERMISSION_RETIRED, "Permission is retired");
    if (newlyAssigned && !"ACTIVE".equals(lifecycle))
      throw error(AuthorizationError.PERMISSION_RETIRED, "Permission cannot be newly assigned");
  }

  protected static void requireNoPreparedRemovalReservation(
      DSLContext tx, UUID tenantId, UUID membershipId) {
    Boolean reserved =
        boolValue(
            tx,
            "SELECT EXISTS(SELECT 1 FROM authorization_membership_removal_reservation WHERE tenant_id=? AND membership_id=? AND state=\'PREPARED\')",
            tenantId,
            membershipId);
    if (Boolean.TRUE.equals(reserved))
      throw error(
          AuthorizationError.LAST_TENANT_OWNER,
          "Owner role mutation conflicts with membership removal preparation");
  }

  protected static void requireActiveMembership(DSLContext tx, UUID tenantId, UUID membershipId) {
    String state =
        stringValue(
            tx,
            "SELECT lifecycle FROM authorization_membership_projection WHERE tenant_id=? AND membership_id=?",
            tenantId,
            membershipId);
    if (!"ACTIVE".equals(state))
      throw error(AuthorizationError.MEMBERSHIP_NOT_ACTIVE, "Membership is not active");
  }

  protected static Integer effectiveOwnerCount(DSLContext tx, UUID tenantId, UUID ownerRoleId) {
    return intValue(
        tx,
        """
      SELECT count(DISTINCT mr.membership_id) FROM authorization_membership_role mr
      JOIN authorization_membership_projection m ON m.tenant_id=mr.tenant_id AND m.membership_id=mr.membership_id AND m.lifecycle='ACTIVE'
      WHERE mr.tenant_id=? AND mr.role_id=? AND NOT EXISTS (
        SELECT 1 FROM authorization_membership_removal_reservation rr WHERE rr.tenant_id=mr.tenant_id AND rr.membership_id=mr.membership_id AND rr.state='PREPARED')
      """,
        tenantId,
        ownerRoleId);
  }

  protected static Replay replay(
      DSLContext tx, UUID requestId, String operation, FingerprintDigest fingerprint) {
    var row =
        tx.fetchOne(
            "SELECT intent_fingerprint,fingerprint_version,fingerprint_key_id,outcome_reference FROM authorization_idempotency_record WHERE request_id=? AND operation=?",
            requestId,
            operation);
    if (row == null) return new Replay(false, null);
    if (!fingerprint.matches(
        row.get("fingerprint_version", String.class),
        row.get("fingerprint_key_id", String.class),
        row.get("intent_fingerprint", byte[].class))) {
      throw error(
          AuthorizationError.REQUEST_ID_CONFLICT, "Request ID conflicts with a different intent");
    }
    return new Replay(true, row.get("outcome_reference", UUID.class));
  }

  protected static UUID requiredReference(Replay replay) {
    if (replay.reference() == null)
      throw error(
          AuthorizationError.AUTHORIZATION_UNAVAILABLE,
          "Idempotent result reference is unavailable");
    return replay.reference();
  }

  protected static void putIdempotency(
      DSLContext tx,
      UUID requestId,
      String operation,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID reference,
      Instant now) {
    execute(
        tx,
        "INSERT INTO authorization_idempotency_record(request_id,tenant_id,operation,intent_fingerprint,fingerprint_version,fingerprint_key_id,outcome_code,outcome_reference,created_at) VALUES (?,?,?,?,?,?,\'ACCEPTED\',?,?)",
        requestId,
        tenantId,
        operation,
        fingerprint.activeValue(),
        fingerprint.version(),
        fingerprint.activeKeyId(),
        reference,
        now);
  }

  protected void audit(
      DSLContext tx,
      String event,
      UUID tenantId,
      UUID actor,
      UUID target,
      String result,
      String reason,
      Instant now) {
    audit(tx, event, null, tenantId, actor, target, result, reason, now);
  }

  protected void audit(
      DSLContext tx,
      String event,
      UUID requestId,
      UUID tenantId,
      UUID actor,
      UUID target,
      String result,
      String reason,
      Instant now) {
    try {
      execute(
          tx,
          "INSERT INTO authorization_audit(audit_id,event_code,request_id,tenant_id,actor_user_id,target_id,result_code,reason,catalog_version,occurred_at) VALUES (?,?,?,?,?,?,?,?,1,?)",
          UUID.randomUUID(),
          event,
          requestId,
          tenantId,
          actor,
          target,
          result,
          reason,
          now);
    } catch (RuntimeException failure) {
      try {
        securityTelemetry.auditFailure();
      } catch (RuntimeException ignored) {
      }
      throw failure;
    }
  }

  protected static String stringValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null ? null : value.toString();
  }

  protected static Integer intValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null ? null : ((Number) value).intValue();
  }

  protected static Boolean boolValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null
        ? null
        : (value instanceof Boolean b ? b : Boolean.valueOf(value.toString()));
  }

  protected static int execute(DSLContext tx, String sql, Object... arguments) {
    Object[] normalized = new Object[arguments.length];
    StringBuilder typedSql = new StringBuilder(sql.length() + arguments.length * 32);
    int argument = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (c == '?' && argument < arguments.length) {
        Object value = arguments[argument];
        if (value instanceof Instant instant) {
          typedSql.append("CAST(? AS TIMESTAMP WITH TIME ZONE)");
          normalized[argument] = instant.atOffset(java.time.ZoneOffset.UTC);
        } else {
          typedSql.append('?');
          normalized[argument] = value;
        }
        argument++;
      } else {
        typedSql.append(c);
      }
    }
    if (argument != arguments.length) throw new IllegalArgumentException("SQL bind count mismatch");
    return tx.execute(typedSql.toString(), normalized);
  }

  protected static AuthorizationException error(AuthorizationError error, String message) {
    return new AuthorizationException(error, message);
  }

  protected record Replay(boolean present, UUID reference) {}

  protected record RoleState(String kind, String lifecycle, long version, String nameKey) {}

  public void recordRejection(
      String eventCode,
      UUID tenantId,
      UUID actorUserId,
      UUID targetId,
      AuthorizationError error,
      String reason,
      Instant now) {
    if (eventCode == null || !eventCode.matches("[A-Z0-9_]{1,64}") || error == null || now == null)
      throw new IllegalArgumentException("Authorization rejection audit input is invalid");
    String bounded = reason == null ? null : reason.substring(0, Math.min(reason.length(), 500));
    dsl.transaction(
        c ->
            audit(
                DSL.using(c),
                eventCode,
                tenantId,
                actorUserId,
                targetId,
                error.name(),
                bounded,
                now));
  }
}
