package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

class AuthorizationQueryPersistence extends AuthorizationPersistenceSupport {
  AuthorizationQueryPersistence(DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    super(dsl, securityTelemetry);
  }

  public boolean checkPermission(UUID tenantId, UUID membershipId, String key) {
    return dsl.transactionResult(c -> check(DSL.using(c), tenantId, membershipId, key));
  }

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

  public RoleModel getRole(ActorContext actor, UUID roleId) {
    return dsl.transactionResult(
        c -> {
          DSLContext tx = DSL.using(c);
          setTenant(tx, actor.tenantId());
          requirePermission(tx, actor, "role.read");
          return role(tx, actor.tenantId(), roleId);
        });
  }

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
}
