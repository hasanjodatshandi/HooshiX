package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

final class AuthorizationRolePersistence extends AuthorizationPersistenceSupport {
  AuthorizationRolePersistence(DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    super(dsl, securityTelemetry);
  }

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
}
