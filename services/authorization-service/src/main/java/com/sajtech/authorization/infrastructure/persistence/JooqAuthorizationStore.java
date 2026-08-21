package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import com.sajtech.authorization.application.port.out.AuthorizationStore;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class JooqAuthorizationStore implements AuthorizationStore {
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
  private static final List<String> TENANT_PERMISSIONS =
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
  private static final List<String> MEMBER_PERMISSIONS =
      List.of("membership.read", "role.read", "tenant.read");
  private final DSLContext dsl;
  private final AuthorizationSecurityTelemetry securityTelemetry;

  public JooqAuthorizationStore(DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    this.dsl = Objects.requireNonNull(dsl);
    this.securityTelemetry = Objects.requireNonNull(securityTelemetry);
  }

  @Override
  public boolean checkPermission(UUID tenantId, UUID membershipId, String key) {
    return dsl.transactionResult(c -> check(DSL.using(c), tenantId, membershipId, key));
  }

  private boolean check(DSLContext tx, UUID tenantId, UUID membershipId, String key) {
    configureCheckPermissionTransaction(tx, tenantId);
    Boolean result =
        boolValue(
            tx, CHECK_PERMISSION_SQL, tenantId, membershipId, key, tenantId, membershipId, key);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public boolean checkPlatformPermission(UUID userId, String key) {
    Boolean result =
        boolValue(
            dsl,
            """
      SELECT EXISTS(
        SELECT 1 FROM authorization_platform_profile_assignment a
        JOIN authorization_permission_definition p ON p.permission_key=? AND p.scope='PLATFORM' AND p.lifecycle='ACTIVE'
        WHERE a.user_id=? AND a.profile_name='platform_admin' AND a.state='ACTIVE'
      )
      """,
            key,
            userId);
    return Boolean.TRUE.equals(result);
  }

  @Override
  public List<PermissionModel> listPermissions(ActorContext actor, int limit, String afterKey) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          requirePermission(tx, actor, "role.read");
          var rows =
              tx.fetch(
                  "SELECT permission_key,scope,lifecycle FROM authorization_permission_definition WHERE scope='TENANT' AND permission_key>? ORDER BY permission_key LIMIT ?",
                  afterKey == null ? "" : afterKey,
                  limit);
          List<PermissionModel> out = new ArrayList<>();
          for (var r : rows)
            out.add(
                new PermissionModel(
                    r.get("permission_key", String.class),
                    r.get("scope", String.class),
                    r.get("lifecycle", String.class)));
          return List.copyOf(out);
        });
  }

  @Override
  public List<RoleModel> listRoles(ActorContext actor, int limit, UUID afterRoleId) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          requirePermission(tx, actor, "role.read");
          var rows =
              tx.fetch(
                  "SELECT role_id FROM authorization_role WHERE tenant_id=? AND (?::uuid IS NULL OR role_id>?::uuid) ORDER BY role_id LIMIT ?",
                  actor.tenantId(),
                  afterRoleId,
                  afterRoleId,
                  limit);
          List<RoleModel> out = new ArrayList<>();
          for (var r : rows) out.add(role(tx, actor.tenantId(), r.get("role_id", UUID.class)));
          return List.copyOf(out);
        });
  }

  @Override
  public RoleModel getRole(ActorContext actor, UUID roleId) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          requirePermission(tx, actor, "role.read");
          return role(tx, actor.tenantId(), roleId);
        });
  }

  @Override
  public MembershipAuthorizationModel getMembershipAuthorization(
      ActorContext actor, UUID membershipId) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          requirePermission(tx, actor, "membership.read");
          String state =
              stringValue(
                  tx,
                  "SELECT lifecycle FROM authorization_membership_projection WHERE tenant_id=? AND membership_id=?",
                  actor.tenantId(),
                  membershipId);
          if (!"ACTIVE".equals(state))
            throw error(AuthorizationError.MEMBERSHIP_NOT_ACTIVE, "Membership is not active");
          List<UUID> roles =
              tx.fetch(
                      "SELECT role_id FROM authorization_membership_role WHERE tenant_id=? AND membership_id=? ORDER BY role_id",
                      actor.tenantId(),
                      membershipId)
                  .getValues("role_id", UUID.class);
          var rows =
              tx.fetch(
                  "SELECT permission_key,decision FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=? ORDER BY permission_key",
                  actor.tenantId(),
                  membershipId);
          List<MembershipAuthorizationModel.PermissionOverrideModel> overrides = new ArrayList<>();
          for (var r : rows)
            overrides.add(
                new MembershipAuthorizationModel.PermissionOverrideModel(
                    r.get("permission_key", String.class), r.get("decision", String.class)));
          return new MembershipAuthorizationModel(membershipId, roles, overrides);
        });
  }

  @Override
  public void provisionOwner(
      UUID requestId,
      FingerprintDigest fp,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          Replay replay = replay(tx, requestId, "PROVISION_OWNER", fp);
          if (replay.present()) return;
          execute(
              tx,
              "INSERT INTO authorization_tenant_projection(tenant_id,lifecycle,updated_at) VALUES (?, 'ACTIVE', ?) ON CONFLICT (tenant_id) DO UPDATE SET lifecycle='ACTIVE',updated_at=EXCLUDED.updated_at",
              tenantId,
              now);
          setTenant(tx, tenantId);
          ensureSystemRoles(tx, tenantId, now);
          execute(
              tx,
              "INSERT INTO authorization_membership_projection(tenant_id,membership_id,user_id,lifecycle,updated_at) VALUES (?,?,?,'ACTIVE',?) ON CONFLICT (tenant_id,membership_id) DO UPDATE SET user_id=EXCLUDED.user_id,lifecycle='ACTIVE',updated_at=EXCLUDED.updated_at",
              tenantId,
              membershipId,
              userId,
              now);
          UUID owner = systemRoleId(tenantId, "tenant_owner");
          execute(
              tx,
              "INSERT INTO authorization_membership_role(tenant_id,membership_id,role_id,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
              tenantId,
              membershipId,
              owner,
              now);
          execute(
              tx,
              "INSERT INTO authorization_owner_safety_guard(tenant_id,guard_version,updated_at) VALUES (?,1,?) ON CONFLICT (tenant_id) DO NOTHING",
              tenantId,
              now);
          audit(
              tx,
              "TENANT_OWNER_PROVISIONED",
              requestId,
              tenantId,
              null,
              membershipId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "PROVISION_OWNER", fp, tenantId, membershipId, now);
        });
  }

  @Override
  public void provisionMember(
      UUID requestId,
      FingerprintDigest fp,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, tenantId);
          Replay replay = replay(tx, requestId, "PROVISION_MEMBER", fp);
          if (replay.present()) return;
          String state =
              stringValue(
                  tx,
                  "SELECT lifecycle FROM authorization_tenant_projection WHERE tenant_id=?",
                  tenantId);
          if (!"ACTIVE".equals(state))
            throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Tenant is not active");
          ensureSystemRoles(tx, tenantId, now);
          execute(
              tx,
              "INSERT INTO authorization_membership_projection(tenant_id,membership_id,user_id,lifecycle,updated_at) VALUES (?,?,?,'ACTIVE',?) ON CONFLICT (tenant_id,membership_id) DO UPDATE SET user_id=EXCLUDED.user_id,lifecycle='ACTIVE',updated_at=EXCLUDED.updated_at",
              tenantId,
              membershipId,
              userId,
              now);
          execute(
              tx,
              "INSERT INTO authorization_membership_role(tenant_id,membership_id,role_id,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
              tenantId,
              membershipId,
              systemRoleId(tenantId, "tenant_member"),
              now);
          audit(
              tx,
              "TENANT_MEMBER_PROVISIONED",
              requestId,
              tenantId,
              null,
              membershipId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "PROVISION_MEMBER", fp, tenantId, membershipId, now);
        });
  }

  @Override
  public void applyTenantLifecycle(
      UUID requestId, FingerprintDigest fp, UUID tenantId, String lifecycle, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          Replay replay = replay(tx, requestId, "TENANT_LIFECYCLE", fp);
          if (replay.present()) return;
          int changed =
              execute(
                  tx,
                  "UPDATE authorization_tenant_projection SET lifecycle=?,updated_at=? WHERE tenant_id=?",
                  lifecycle,
                  now,
                  tenantId);
          if (changed != 1)
            throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Tenant is unknown");
          audit(
              tx,
              "TENANT_LIFECYCLE_APPLIED",
              requestId,
              tenantId,
              null,
              tenantId,
              "ACCEPTED",
              lifecycle,
              now);
          putIdempotency(tx, requestId, "TENANT_LIFECYCLE", fp, tenantId, tenantId, now);
        });
  }

  @Override
  public void prepareMembershipRemoval(
      UUID requestId, FingerprintDigest fp, UUID tenantId, UUID membershipId, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, tenantId);
          Replay replay = replay(tx, requestId, "PREPARE_REMOVAL", fp);
          if (replay.present()) return;
          lockOwnerGuard(tx, tenantId);
          String member =
              stringValue(
                  tx,
                  "SELECT lifecycle FROM authorization_membership_projection WHERE tenant_id=? AND membership_id=?",
                  tenantId,
                  membershipId);
          if (!"ACTIVE".equals(member))
            throw error(AuthorizationError.MEMBERSHIP_NOT_ACTIVE, "Membership is not active");
          UUID owner = systemRoleId(tenantId, "tenant_owner");
          Boolean targetOwner =
              boolValue(
                  tx,
                  "SELECT EXISTS(SELECT 1 FROM authorization_membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?)",
                  tenantId,
                  membershipId,
                  owner);
          if (Boolean.TRUE.equals(targetOwner)) {
            Integer owners =
                intValue(
                    tx,
                    """
          SELECT count(DISTINCT mr.membership_id) FROM authorization_membership_role mr
          JOIN authorization_membership_projection m ON m.tenant_id=mr.tenant_id AND m.membership_id=mr.membership_id AND m.lifecycle='ACTIVE'
          WHERE mr.tenant_id=? AND mr.role_id=? AND NOT EXISTS (
            SELECT 1 FROM authorization_membership_removal_reservation rr WHERE rr.tenant_id=mr.tenant_id AND rr.membership_id=mr.membership_id AND rr.state='PREPARED')
          """,
                    tenantId,
                    owner);
            if (owners == null || owners <= 1)
              throw error(
                  AuthorizationError.LAST_TENANT_OWNER, "Last tenant owner cannot be removed");
          }
          execute(
              tx,
              "INSERT INTO authorization_membership_removal_reservation(tenant_id,membership_id,request_id,state,created_at) VALUES (?,?,?,'PREPARED',?)",
              tenantId,
              membershipId,
              requestId,
              now);
          audit(
              tx,
              "MEMBERSHIP_REMOVAL_PREPARED",
              requestId,
              tenantId,
              null,
              membershipId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "PREPARE_REMOVAL", fp, tenantId, membershipId, now);
        });
  }

  @Override
  public void finalizeMembershipRemoval(
      UUID requestId, FingerprintDigest fp, UUID tenantId, UUID membershipId, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, tenantId);
          Replay replay = replay(tx, requestId, "FINALIZE_REMOVAL", fp);
          if (replay.present()) return;
          lockOwnerGuard(tx, tenantId);
          String state =
              stringValue(
                  tx,
                  "SELECT state FROM authorization_membership_removal_reservation WHERE tenant_id=? AND membership_id=? AND request_id=? FOR UPDATE",
                  tenantId,
                  membershipId,
                  requestId);
          if (!"PREPARED".equals(state) && !"FINALIZED".equals(state))
            throw error(AuthorizationError.INVALID_ARGUMENT, "Removal preparation is missing");
          if ("PREPARED".equals(state)) {
            execute(
                tx,
                "DELETE FROM authorization_membership_role WHERE tenant_id=? AND membership_id=?",
                tenantId,
                membershipId);
            execute(
                tx,
                "DELETE FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=?",
                tenantId,
                membershipId);
            execute(
                tx,
                "UPDATE authorization_membership_projection SET lifecycle='REMOVED',updated_at=? WHERE tenant_id=? AND membership_id=?",
                now,
                tenantId,
                membershipId);
            execute(
                tx,
                "UPDATE authorization_membership_removal_reservation SET state='FINALIZED',resolved_at=? WHERE tenant_id=? AND membership_id=? AND request_id=?",
                now,
                tenantId,
                membershipId,
                requestId);
          }
          audit(
              tx,
              "MEMBERSHIP_REMOVAL_FINALIZED",
              requestId,
              tenantId,
              null,
              membershipId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "FINALIZE_REMOVAL", fp, tenantId, membershipId, now);
        });
  }

  @Override
  public void cancelMembershipRemoval(
      UUID requestId, FingerprintDigest fp, UUID tenantId, UUID membershipId, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, tenantId);
          Replay replay = replay(tx, requestId, "CANCEL_REMOVAL", fp);
          if (replay.present()) return;
          lockOwnerGuard(tx, tenantId);
          String state =
              stringValue(
                  tx,
                  "SELECT state FROM authorization_membership_removal_reservation WHERE tenant_id=? AND membership_id=? AND request_id=? FOR UPDATE",
                  tenantId,
                  membershipId,
                  requestId);
          if (state == null)
            throw error(AuthorizationError.INVALID_ARGUMENT, "Removal preparation is missing");
          if ("FINALIZED".equals(state))
            throw error(
                AuthorizationError.INVALID_ARGUMENT, "Finalized removal cannot be cancelled");
          if ("PREPARED".equals(state))
            execute(
                tx,
                "UPDATE authorization_membership_removal_reservation SET state='CANCELLED',resolved_at=? WHERE tenant_id=? AND membership_id=? AND request_id=?",
                now,
                tenantId,
                membershipId,
                requestId);
          audit(
              tx,
              "MEMBERSHIP_REMOVAL_CANCELLED",
              requestId,
              tenantId,
              null,
              membershipId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "CANCEL_REMOVAL", fp, tenantId, membershipId, now);
        });
  }

  @Override
  public void projectPermissionCatalog(
      List<PermissionModel> permissions, int version, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          for (PermissionModel p : permissions)
            execute(
                tx,
                "INSERT INTO authorization_permission_definition(permission_key,scope,lifecycle,catalog_version,created_at,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT(permission_key) DO UPDATE SET scope=EXCLUDED.scope,lifecycle=EXCLUDED.lifecycle,catalog_version=EXCLUDED.catalog_version,updated_at=EXCLUDED.updated_at",
                p.key(),
                p.scope(),
                p.lifecycle(),
                version,
                now,
                now);
          List<String> keys = permissions.stream().map(PermissionModel::key).toList();
          var existing =
              tx.fetch(
                      "SELECT permission_key FROM authorization_permission_definition WHERE catalog_version<=?",
                      version)
                  .getValues("permission_key", String.class);
          if (!new HashSet<>(existing).equals(new HashSet<>(keys)))
            throw error(
                AuthorizationError.AUTHORIZATION_UNAVAILABLE,
                "Permission catalog projection contains unknown retained keys");
        });
  }

  private void ensureSystemRoles(DSLContext tx, UUID tenantId, Instant now) {
    systemRole(tx, tenantId, "tenant_owner", TENANT_PERMISSIONS, now);
    List<String> admin =
        TENANT_PERMISSIONS.stream()
            .filter(p -> !p.equals("tenant.delete") && !p.equals("membership.owner.assign"))
            .toList();
    systemRole(tx, tenantId, "tenant_admin", admin, now);
    systemRole(tx, tenantId, "tenant_member", MEMBER_PERMISSIONS, now);
  }

  private void systemRole(
      DSLContext tx, UUID tenantId, String name, List<String> permissions, Instant now) {
    UUID roleId = systemRoleId(tenantId, name);
    execute(
        tx,
        "INSERT INTO authorization_role(tenant_id,role_id,name,name_key,description,kind,lifecycle,version,created_at,updated_at) VALUES (?,?,?,?,?,'SYSTEM','ACTIVE',1,?,?) ON CONFLICT (tenant_id,role_id) DO NOTHING",
        tenantId,
        roleId,
        name,
        name,
        "",
        now,
        now);
    for (String permission : permissions)
      execute(
          tx,
          "INSERT INTO authorization_role_permission(tenant_id,role_id,permission_key,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
          tenantId,
          roleId,
          permission,
          now);
  }

  private static UUID systemRoleId(UUID tenantId, String name) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  ("hooshix:authorization:system-role:v1:" + tenantId + ":" + name)
                      .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
      digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
      java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(digest);
      return new UUID(bytes.getLong(), bytes.getLong());
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  static void configureCheckPermissionTransaction(DSLContext tx, UUID tenantId) {
    setTenant(tx, tenantId);
    tx.fetchValue("SELECT set_config('statement_timeout', '100ms', true)");
  }

  private static void setTenant(DSLContext tx, UUID tenantId) {
    tx.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", tenantId.toString());
  }

  private void requirePermission(DSLContext tx, ActorContext actor, String permission) {
    if (!check(tx, actor.tenantId(), actor.membershipId(), permission))
      throw error(AuthorizationError.AUTHORIZATION_DENIED, "Authorization denied");
  }

  private static void lockTenantManagement(DSLContext tx, UUID tenantId) {
    var row =
        tx.fetchOne(
            "SELECT tenant_id FROM authorization_tenant_projection WHERE tenant_id=? FOR UPDATE",
            tenantId);
    UUID locked = row == null ? null : row.get("tenant_id", UUID.class);
    if (locked == null)
      throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Tenant is unknown");
  }

  private static void lockOwnerGuard(DSLContext tx, UUID tenantId) {
    Integer guard =
        intValue(
            tx,
            "SELECT 1 FROM authorization_owner_safety_guard WHERE tenant_id=? FOR UPDATE",
            tenantId);
    if (guard == null)
      throw error(AuthorizationError.TENANT_NOT_AUTHORIZABLE, "Owner safety guard is missing");
  }

  @Override
  public RoleModel createRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      String name,
      String nameKey,
      String description,
      List<String> permissions,
      Instant now) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "CREATE_ROLE", fp);
          if (replay.present()) return role(tx, actor.tenantId(), requiredReference(replay));
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "role.create");
          Integer count =
              intValue(
                  tx,
                  "SELECT count(*) FROM authorization_role WHERE tenant_id=? AND kind='CUSTOM'",
                  actor.tenantId());
          if (count != null && count >= 100)
            throw error(AuthorizationError.LIMIT_EXCEEDED, "Custom role limit reached");
          requireGrantablePermissions(tx, actor, permissions, true);
          UUID roleId = UUID.randomUUID();
          int inserted =
              execute(
                  tx,
                  """
        INSERT INTO authorization_role(tenant_id,role_id,name,name_key,description,kind,lifecycle,version,created_at,updated_at)
        SELECT ?,?,?,?,?,'CUSTOM','ACTIVE',1,?,?
        WHERE NOT EXISTS(SELECT 1 FROM authorization_role WHERE tenant_id=? AND name_key=?)
        """,
                  actor.tenantId(),
                  roleId,
                  name,
                  nameKey,
                  description,
                  now,
                  now,
                  actor.tenantId(),
                  nameKey);
          if (inserted != 1)
            throw error(AuthorizationError.ROLE_NAME_CONFLICT, "Role name is already used");
          for (String permission : permissions)
            execute(
                tx,
                "INSERT INTO authorization_role_permission(tenant_id,role_id,permission_key,created_at) VALUES (?,?,?,?)",
                actor.tenantId(),
                roleId,
                permission,
                now);
          audit(
              tx,
              "ROLE_CREATED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              roleId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "CREATE_ROLE", fp, actor.tenantId(), roleId, now);
          return role(tx, actor.tenantId(), roleId);
        });
  }

  @Override
  public RoleModel updateRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID roleId,
      long expectedVersion,
      String name,
      String nameKey,
      String description,
      Instant now) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "UPDATE_ROLE", fp);
          if (replay.present()) return role(tx, actor.tenantId(), requiredReference(replay));
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "role.update");
          RoleState state = lockRole(tx, actor.tenantId(), roleId);
          requireMutable(state, expectedVersion);
          Integer conflict =
              intValue(
                  tx,
                  "SELECT count(*) FROM authorization_role WHERE tenant_id=? AND name_key=? AND role_id<>?",
                  actor.tenantId(),
                  nameKey,
                  roleId);
          if (conflict != null && conflict > 0)
            throw error(AuthorizationError.ROLE_NAME_CONFLICT, "Role name is already used");
          execute(
              tx,
              "UPDATE authorization_role SET name=?,name_key=?,description=?,version=version+1,updated_at=? WHERE tenant_id=? AND role_id=?",
              name,
              nameKey,
              description,
              now,
              actor.tenantId(),
              roleId);
          audit(
              tx,
              "ROLE_UPDATED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              roleId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "UPDATE_ROLE", fp, actor.tenantId(), roleId, now);
          return role(tx, actor.tenantId(), roleId);
        });
  }

  @Override
  public RoleModel archiveRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID roleId,
      long expectedVersion,
      Instant now) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "ARCHIVE_ROLE", fp);
          if (replay.present()) return role(tx, actor.tenantId(), requiredReference(replay));
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "role.archive");
          RoleState state = lockRole(tx, actor.tenantId(), roleId);
          requireMutable(state, expectedVersion);
          execute(
              tx,
              "UPDATE authorization_role SET lifecycle='ARCHIVED',version=version+1,updated_at=? WHERE tenant_id=? AND role_id=?",
              now,
              actor.tenantId(),
              roleId);
          audit(
              tx,
              "ROLE_ARCHIVED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              roleId,
              "ACCEPTED",
              null,
              now);
          putIdempotency(tx, requestId, "ARCHIVE_ROLE", fp, actor.tenantId(), roleId, now);
          return role(tx, actor.tenantId(), roleId);
        });
  }

  @Override
  public List<String> rolePermissionKeysForQuota(UUID tenantId, UUID roleId) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, tenantId);
          return List.copyOf(
              tx.fetch(
                      "SELECT permission_key FROM authorization_role_permission WHERE tenant_id=? AND role_id=? ORDER BY permission_key",
                      tenantId,
                      roleId)
                  .getValues("permission_key", String.class));
        });
  }

  @Override
  public RoleModel replaceRolePermissions(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID roleId,
      long expectedVersion,
      List<String> permissions,
      String reason,
      Instant now) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "REPLACE_ROLE_PERMISSIONS", fp);
          if (replay.present()) return role(tx, actor.tenantId(), requiredReference(replay));
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "role.permission.manage");
          RoleState state = lockRole(tx, actor.tenantId(), roleId);
          requireMutable(state, expectedVersion);
          List<String> current =
              tx.fetch(
                      "SELECT permission_key FROM authorization_role_permission WHERE tenant_id=? AND role_id=?",
                      actor.tenantId(),
                      roleId)
                  .getValues("permission_key", String.class);
          Set<String> added = new HashSet<>(permissions);
          added.removeAll(current);
          requireGrantablePermissions(tx, actor, List.copyOf(added), true);
          execute(
              tx,
              "DELETE FROM authorization_role_permission WHERE tenant_id=? AND role_id=?",
              actor.tenantId(),
              roleId);
          for (String permission : permissions)
            execute(
                tx,
                "INSERT INTO authorization_role_permission(tenant_id,role_id,permission_key,created_at) VALUES (?,?,?,?)",
                actor.tenantId(),
                roleId,
                permission,
                now);
          execute(
              tx,
              "UPDATE authorization_role SET version=version+1,updated_at=? WHERE tenant_id=? AND role_id=?",
              now,
              actor.tenantId(),
              roleId);
          audit(
              tx,
              "ROLE_PERMISSIONS_REPLACED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              roleId,
              "ACCEPTED",
              reason,
              now);
          putIdempotency(
              tx, requestId, "REPLACE_ROLE_PERMISSIONS", fp, actor.tenantId(), roleId, now);
          return role(tx, actor.tenantId(), roleId);
        });
  }

  @Override
  public void assignRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "ASSIGN_ROLE", fp);
          if (replay.present()) return;
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "membership.role.assign");
          requireActiveMembership(tx, actor.tenantId(), membershipId);
          RoleState roleState = lockRole(tx, actor.tenantId(), roleId);
          if (!"ACTIVE".equals(roleState.lifecycle()))
            throw error(AuthorizationError.ROLE_ARCHIVED, "Role is archived");
          List<String> grants =
              tx.fetch(
                      "SELECT permission_key FROM authorization_role_permission WHERE tenant_id=? AND role_id=?",
                      actor.tenantId(),
                      roleId)
                  .getValues("permission_key", String.class);
          for (String permission : grants) requirePermission(tx, actor, permission);
          if ("tenant_owner".equals(roleState.nameKey())) {
            requirePermission(tx, actor, "membership.owner.assign");
            lockOwnerGuard(tx, actor.tenantId());
            requireNoPreparedRemovalReservation(tx, actor.tenantId(), membershipId);
          }
          Integer count =
              intValue(
                  tx,
                  "SELECT count(*) FROM authorization_membership_role WHERE tenant_id=? AND membership_id=?",
                  actor.tenantId(),
                  membershipId);
          if (count != null && count >= 20)
            throw error(AuthorizationError.LIMIT_EXCEEDED, "Membership role limit reached");
          execute(
              tx,
              "INSERT INTO authorization_membership_role(tenant_id,membership_id,role_id,created_at) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
              actor.tenantId(),
              membershipId,
              roleId,
              now);
          audit(
              tx,
              "MEMBERSHIP_ROLE_ASSIGNED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              membershipId,
              "ACCEPTED",
              reason,
              now);
          putIdempotency(tx, requestId, "ASSIGN_ROLE", fp, actor.tenantId(), membershipId, now);
        });
  }

  @Override
  public void removeRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "REMOVE_ROLE", fp);
          if (replay.present()) return;
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "membership.role.assign");
          requireActiveMembership(tx, actor.tenantId(), membershipId);
          RoleState roleState = lockRole(tx, actor.tenantId(), roleId);
          if ("tenant_owner".equals(roleState.nameKey())) {
            requirePermission(tx, actor, "membership.owner.assign");
            lockOwnerGuard(tx, actor.tenantId());
            requireNoPreparedRemovalReservation(tx, actor.tenantId(), membershipId);
            Integer owners = effectiveOwnerCount(tx, actor.tenantId(), roleId);
            Boolean assigned =
                boolValue(
                    tx,
                    "SELECT EXISTS(SELECT 1 FROM authorization_membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?)",
                    actor.tenantId(),
                    membershipId,
                    roleId);
            if (Boolean.TRUE.equals(assigned) && (owners == null || owners <= 1))
              throw error(
                  AuthorizationError.LAST_TENANT_OWNER, "Last tenant owner cannot be removed");
          }
          execute(
              tx,
              "DELETE FROM authorization_membership_role WHERE tenant_id=? AND membership_id=? AND role_id=?",
              actor.tenantId(),
              membershipId,
              roleId);
          audit(
              tx,
              "MEMBERSHIP_ROLE_REMOVED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              membershipId,
              "ACCEPTED",
              reason,
              now);
          putIdempotency(tx, requestId, "REMOVE_ROLE", fp, actor.tenantId(), membershipId, now);
        });
  }

  @Override
  public void setOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID membershipId,
      String permissionKey,
      String decision,
      String reason,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "SET_OVERRIDE", fp);
          if (replay.present()) return;
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "membership.permission.manage");
          requireActiveMembership(tx, actor.tenantId(), membershipId);
          requirePermissionDefinition(tx, permissionKey, true);
          if ("GRANT".equals(decision)) requirePermission(tx, actor, permissionKey);
          Integer count =
              intValue(
                  tx,
                  "SELECT count(*) FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=?",
                  actor.tenantId(),
                  membershipId);
          String existing =
              stringValue(
                  tx,
                  "SELECT decision FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=? AND permission_key=?",
                  actor.tenantId(),
                  membershipId,
                  permissionKey);
          if (existing == null && count != null && count >= 100)
            throw error(AuthorizationError.LIMIT_EXCEEDED, "Membership override limit reached");
          execute(
              tx,
              "INSERT INTO authorization_membership_permission_override(tenant_id,membership_id,permission_key,decision,created_at,updated_at) VALUES (?,?,?,?,?,?) ON CONFLICT (tenant_id,membership_id,permission_key) DO UPDATE SET decision=EXCLUDED.decision,updated_at=EXCLUDED.updated_at",
              actor.tenantId(),
              membershipId,
              permissionKey,
              decision,
              now,
              now);
          audit(
              tx,
              "MEMBERSHIP_OVERRIDE_SET",
              requestId,
              actor.tenantId(),
              actor.userId(),
              membershipId,
              "ACCEPTED",
              reason,
              now);
          putIdempotency(tx, requestId, "SET_OVERRIDE", fp, actor.tenantId(), membershipId, now);
        });
  }

  @Override
  public void removeOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fp,
      UUID membershipId,
      String permissionKey,
      String reason,
      Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          Replay replay = replay(tx, requestId, "REMOVE_OVERRIDE", fp);
          if (replay.present()) return;
          lockTenantManagement(tx, actor.tenantId());
          requirePermission(tx, actor, "membership.permission.manage");
          requireActiveMembership(tx, actor.tenantId(), membershipId);
          String existing =
              stringValue(
                  tx,
                  "SELECT decision FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=? AND permission_key=? FOR UPDATE",
                  actor.tenantId(),
                  membershipId,
                  permissionKey);
          if ("DENY".equals(existing)) requirePermission(tx, actor, permissionKey);
          execute(
              tx,
              "DELETE FROM authorization_membership_permission_override WHERE tenant_id=? AND membership_id=? AND permission_key=?",
              actor.tenantId(),
              membershipId,
              permissionKey);
          audit(
              tx,
              "MEMBERSHIP_OVERRIDE_REMOVED",
              requestId,
              actor.tenantId(),
              actor.userId(),
              membershipId,
              "ACCEPTED",
              reason,
              now);
          putIdempotency(tx, requestId, "REMOVE_OVERRIDE", fp, actor.tenantId(), membershipId, now);
        });
  }

  private RoleModel role(DSLContext tx, UUID tenantId, UUID roleId) {
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

  private static RoleState lockRole(DSLContext tx, UUID tenantId, UUID roleId) {
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

  private static void requireMutable(RoleState state, long expectedVersion) {
    if ("SYSTEM".equals(state.kind()))
      throw error(AuthorizationError.SYSTEM_ROLE_IMMUTABLE, "SYSTEM role is immutable");
    if (!"ACTIVE".equals(state.lifecycle()))
      throw error(AuthorizationError.ROLE_ARCHIVED, "Role is archived");
    if (state.version() != expectedVersion)
      throw error(AuthorizationError.STALE_ROLE_VERSION, "Role version is stale");
  }

  private void requireGrantablePermissions(
      DSLContext tx, ActorContext actor, List<String> permissions, boolean requireActorAuthority) {
    if (permissions.size() > 200)
      throw error(AuthorizationError.LIMIT_EXCEEDED, "Permission limit reached");
    for (String permission : permissions) {
      requirePermissionDefinition(tx, permission, true);
      if (requireActorAuthority) requirePermission(tx, actor, permission);
    }
  }

  private static void requirePermissionDefinition(
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

  private static void requireNoPreparedRemovalReservation(
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

  private static void requireActiveMembership(DSLContext tx, UUID tenantId, UUID membershipId) {
    String state =
        stringValue(
            tx,
            "SELECT lifecycle FROM authorization_membership_projection WHERE tenant_id=? AND membership_id=?",
            tenantId,
            membershipId);
    if (!"ACTIVE".equals(state))
      throw error(AuthorizationError.MEMBERSHIP_NOT_ACTIVE, "Membership is not active");
  }

  private static Integer effectiveOwnerCount(DSLContext tx, UUID tenantId, UUID ownerRoleId) {
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

  private static Replay replay(
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

  private static UUID requiredReference(Replay replay) {
    if (replay.reference() == null)
      throw error(
          AuthorizationError.AUTHORIZATION_UNAVAILABLE,
          "Idempotent result reference is unavailable");
    return replay.reference();
  }

  private static void putIdempotency(
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

  private void audit(
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

  private void audit(
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

  private static String stringValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null ? null : value.toString();
  }

  private static Integer intValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null ? null : ((Number) value).intValue();
  }

  private static Boolean boolValue(DSLContext tx, String sql, Object... bindings) {
    Object value = tx.fetchValue(sql, bindings);
    return value == null
        ? null
        : (value instanceof Boolean b ? b : Boolean.valueOf(value.toString()));
  }

  private static int execute(DSLContext tx, String sql, Object... arguments) {
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

  private static AuthorizationException error(AuthorizationError error, String message) {
    return new AuthorizationException(error, message);
  }

  private record Replay(boolean present, UUID reference) {}

  private record RoleState(String kind, String lifecycle, long version, String nameKey) {}

  @Override
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
