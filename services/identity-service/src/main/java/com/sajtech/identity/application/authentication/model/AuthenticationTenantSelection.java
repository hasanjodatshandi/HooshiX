package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record AuthenticationTenantSelection(
    AuthenticationSessionMode mode, UUID tenantId, UUID membershipId) {
  public AuthenticationTenantSelection {
    if (mode == null)
      throw new IllegalArgumentException("Authentication tenant selection mode is required");
    if (mode == AuthenticationSessionMode.TENANT_AUTHENTICATED
        && (tenantId == null || membershipId == null)) {
      throw new IllegalArgumentException("Tenant-authenticated selection is incomplete");
    }
    if (mode == AuthenticationSessionMode.AUTHENTICATED_ONBOARDING
        && (tenantId != null || membershipId != null)) {
      throw new IllegalArgumentException("Onboarding selection must not contain tenant context");
    }
  }

  public static AuthenticationTenantSelection onboarding() {
    return new AuthenticationTenantSelection(
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING, null, null);
  }

  public static AuthenticationTenantSelection tenant(UUID tenantId, UUID membershipId) {
    return new AuthenticationTenantSelection(
        AuthenticationSessionMode.TENANT_AUTHENTICATED, tenantId, membershipId);
  }
}
