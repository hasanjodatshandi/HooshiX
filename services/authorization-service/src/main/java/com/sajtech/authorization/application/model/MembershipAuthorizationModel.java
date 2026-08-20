package com.sajtech.authorization.application.model;

import java.util.List;
import java.util.UUID;

public record MembershipAuthorizationModel(
    UUID membershipId, List<UUID> roleIds, List<PermissionOverrideModel> overrides) {
  public MembershipAuthorizationModel {
    roleIds = List.copyOf(roleIds);
    overrides = List.copyOf(overrides);
  }

  public record PermissionOverrideModel(String permissionKey, String decision) {}
}
