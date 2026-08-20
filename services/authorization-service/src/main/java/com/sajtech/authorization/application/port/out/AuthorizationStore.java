package com.sajtech.authorization.application.port.out;

import com.sajtech.authorization.application.model.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuthorizationStore {
  boolean checkPermission(UUID tenantId, UUID membershipId, String permissionKey);

  boolean checkPlatformPermission(UUID userId, String permissionKey);

  List<PermissionModel> listPermissions(ActorContext actor, int limit, String afterKey);

  List<RoleModel> listRoles(ActorContext actor, int limit, UUID afterRoleId);

  RoleModel getRole(ActorContext actor, UUID roleId);

  MembershipAuthorizationModel getMembershipAuthorization(ActorContext actor, UUID membershipId);

  RoleModel createRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      String name,
      String nameKey,
      String description,
      List<String> permissions,
      Instant now);

  RoleModel updateRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      String name,
      String nameKey,
      String description,
      Instant now);

  RoleModel archiveRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      Instant now);

  List<String> rolePermissionKeysForQuota(UUID tenantId, UUID roleId);

  RoleModel replaceRolePermissions(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID roleId,
      long expectedVersion,
      List<String> permissions,
      String reason,
      Instant now);

  void assignRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now);

  void removeRole(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      UUID roleId,
      String reason,
      Instant now);

  void setOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      String permissionKey,
      String decision,
      String reason,
      Instant now);

  void removeOverride(
      ActorContext actor,
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID membershipId,
      String permissionKey,
      String reason,
      Instant now);

  void provisionOwner(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now);

  void provisionMember(
      UUID requestId,
      FingerprintDigest fingerprint,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      Instant now);

  void applyTenantLifecycle(
      UUID requestId, FingerprintDigest fingerprint, UUID tenantId, String lifecycle, Instant now);

  void prepareMembershipRemoval(
      UUID requestId, FingerprintDigest fingerprint, UUID tenantId, UUID membershipId, Instant now);

  void finalizeMembershipRemoval(
      UUID requestId, FingerprintDigest fingerprint, UUID tenantId, UUID membershipId, Instant now);

  void cancelMembershipRemoval(
      UUID requestId, FingerprintDigest fingerprint, UUID tenantId, UUID membershipId, Instant now);

  void projectPermissionCatalog(List<PermissionModel> permissions, int version, Instant now);

  void recordRejection(
      String eventCode,
      UUID tenantId,
      UUID actorUserId,
      UUID targetId,
      com.sajtech.authorization.application.AuthorizationError error,
      String reason,
      Instant now);
}
