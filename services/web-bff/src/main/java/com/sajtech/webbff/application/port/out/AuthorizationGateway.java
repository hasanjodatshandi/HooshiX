package com.sajtech.webbff.application.port.out;

import java.util.*;

public interface AuthorizationGateway {
  List<PermissionDto> permissions(String token, int size, String page);

  RolePage roles(String token, int size, String page);

  RoleDto role(String token, UUID roleId);

  MembershipAuthorizationDto membership(String token, UUID membershipId);

  RoleDto createRole(
      String token, UUID requestId, String name, String description, List<String> permissions);

  RoleDto updateRole(
      String token, UUID requestId, UUID roleId, long version, String name, String description);

  RoleDto archiveRole(String token, UUID requestId, UUID roleId, long version);

  RoleDto replacePermissions(
      String token,
      UUID requestId,
      UUID roleId,
      long version,
      List<String> permissions,
      String reason);

  void assignRole(String token, UUID requestId, UUID membershipId, UUID roleId, String reason);

  void removeRole(String token, UUID requestId, UUID membershipId, UUID roleId, String reason);

  void setOverride(
      String token,
      UUID requestId,
      UUID membershipId,
      String permission,
      String decision,
      String reason);

  void removeOverride(
      String token, UUID requestId, UUID membershipId, String permission, String reason);

  record PermissionDto(String key, String scope, String lifecycle) {}

  record RoleDto(
      UUID roleId,
      String name,
      String description,
      String kind,
      String lifecycle,
      long version,
      List<String> permissions) {
    public RoleDto {
      permissions = List.copyOf(permissions);
    }
  }

  record RolePage(List<RoleDto> roles, String nextPageToken) {
    public RolePage {
      roles = List.copyOf(roles);
    }
  }

  record OverrideDto(String permissionKey, String decision) {}

  record MembershipAuthorizationDto(
      UUID membershipId, List<UUID> roleIds, List<OverrideDto> overrides) {
    public MembershipAuthorizationDto {
      roleIds = List.copyOf(roleIds);
      overrides = List.copyOf(overrides);
    }
  }
}
