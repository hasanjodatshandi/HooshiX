package com.sajtech.authorization.application.model;

import java.util.List;
import java.util.UUID;

public record RoleModel(UUID roleId, String name, String description, String kind, String lifecycle, long version, List<String> permissionKeys) {
  public RoleModel { permissionKeys = List.copyOf(permissionKeys); }
}
