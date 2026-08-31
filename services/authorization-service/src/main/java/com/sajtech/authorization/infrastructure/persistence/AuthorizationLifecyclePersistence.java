package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

final class AuthorizationLifecyclePersistence extends AuthorizationPersistenceSupport {
  AuthorizationLifecyclePersistence(
      DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    super(dsl, securityTelemetry);
  }

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
          setTenant(tx, tenantId);
          execute(
              tx,
              "INSERT INTO authorization_tenant_projection(tenant_id,lifecycle,updated_at) VALUES (?, 'ACTIVE', ?) ON CONFLICT (tenant_id) DO UPDATE SET lifecycle='ACTIVE',updated_at=EXCLUDED.updated_at",
              tenantId,
              now);
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

  public void applyTenantLifecycle(
      UUID requestId, FingerprintDigest fp, UUID tenantId, String lifecycle, Instant now) {
    dsl.transaction(
        c -> {
          DSLContext tx = DSL.using(c);
          Replay replay = replay(tx, requestId, "TENANT_LIFECYCLE", fp);
          if (replay.present()) return;
          setTenant(tx, tenantId);
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
}
