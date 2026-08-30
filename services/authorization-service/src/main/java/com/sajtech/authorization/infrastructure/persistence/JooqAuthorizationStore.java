package com.sajtech.authorization.infrastructure.persistence;

import com.sajtech.authorization.application.model.*;
import com.sajtech.authorization.application.port.out.AuthorizationSecurityTelemetry;
import com.sajtech.authorization.application.port.out.AuthorizationStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqAuthorizationStore extends AuthorizationQueryPersistence
    implements AuthorizationStore {
  static final String CHECK_PERMISSION_SQL = AuthorizationPersistenceSupport.CHECK_PERMISSION_SQL;

  private final AuthorizationLifecyclePersistence lifecycle;
  private final AuthorizationRolePersistence roles;

  public JooqAuthorizationStore(DSLContext dsl, AuthorizationSecurityTelemetry securityTelemetry) {
    super(dsl, securityTelemetry);
    Objects.requireNonNull(dsl);
    Objects.requireNonNull(securityTelemetry);
    lifecycle = new AuthorizationLifecyclePersistence(dsl, securityTelemetry);
    roles = new AuthorizationRolePersistence(dsl, securityTelemetry);
  }

  @Override
  public void provisionOwner(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now) {
    lifecycle.provisionOwner(requestId, fingerprint, tenantId, membershipId, userId, now);
  }

  @Override
  public void provisionMember(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now) {
    lifecycle.provisionMember(requestId, fingerprint, tenantId, membershipId, userId, now);
  }

  @Override
  public void applyTenantLifecycle(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      String lifecycleState,
      Instant now) {
    lifecycle.applyTenantLifecycle(requestId, fingerprint, tenantId, lifecycleState, now);
  }

  @Override
  public void prepareMembershipRemoval(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      Instant now) {
    lifecycle.prepareMembershipRemoval(requestId, fingerprint, tenantId, membershipId, now);
  }

  @Override
  public void finalizeMembershipRemoval(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      Instant now) {
    lifecycle.finalizeMembershipRemoval(requestId, fingerprint, tenantId, membershipId, now);
  }

  @Override
  public void cancelMembershipRemoval(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      Instant now) {
    lifecycle.cancelMembershipRemoval(requestId, fingerprint, tenantId, membershipId, now);
  }

  @Override
  public void projectPermissionCatalog(
      List<PermissionModel> permissions, int version, Instant now) {
    lifecycle.projectPermissionCatalog(permissions, version, now);
  }

  @Override
  public RoleModel createRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      String name,
      String nameKey,
      String description,
      List<String> permissions,
      Instant now) {
    return roles.createRole(
        actor, requestId, fingerprint, name, nameKey, description, permissions, now);
  }

  @Override
  public RoleModel updateRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      String name,
      String nameKey,
      String description,
      Instant now) {
    return roles.updateRole(
        actor, requestId, fingerprint, roleId, expectedVersion, name, nameKey, description, now);
  }

  @Override
  public RoleModel archiveRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      Instant now) {
    return roles.archiveRole(actor, requestId, fingerprint, roleId, expectedVersion, now);
  }

  @Override
  public List<String> rolePermissionKeysForQuota(UUID tenantId, UUID roleId) {
    return roles.rolePermissionKeysForQuota(tenantId, roleId);
  }

  @Override
  public RoleModel replaceRolePermissions(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      List<String> permissions,
      String reason,
      Instant now) {
    return roles.replaceRolePermissions(
        actor, requestId, fingerprint, roleId, expectedVersion, permissions, reason, now);
  }

  @Override
  public void assignRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now) {
    roles.assignRole(actor, requestId, fingerprint, membershipId, roleId, reason, now);
  }

  @Override
  public void removeRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now) {
    roles.removeRole(actor, requestId, fingerprint, membershipId, roleId, reason, now);
  }

  @Override
  public void setOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      String permissionKey,
      String decision,
      String reason,
      Instant now) {
    roles.setOverride(
        actor, requestId, fingerprint, membershipId, permissionKey, decision, reason, now);
  }

  @Override
  public void removeOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      String permissionKey,
      String reason,
      Instant now) {
    roles.removeOverride(actor, requestId, fingerprint, membershipId, permissionKey, reason, now);
  }
}
