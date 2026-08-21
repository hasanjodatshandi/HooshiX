package com.sajtech.identity.application.tenant.port.out;

import java.util.UUID;

public interface AuthorizationTenantPort {
  void checkPermission(UUID tenantId, UUID membershipId, String permissionKey);

  void provisionOwner(UUID requestId, UUID tenantId, UUID membershipId, UUID userId);

  void provisionMember(UUID requestId, UUID tenantId, UUID membershipId, UUID userId);

  void applyTenantLifecycle(UUID requestId, UUID tenantId, String lifecycle);

  void prepareMembershipRemoval(UUID requestId, UUID tenantId, UUID membershipId);

  void finalizeMembershipRemoval(UUID requestId, UUID tenantId, UUID membershipId);

  void cancelMembershipRemoval(UUID requestId, UUID tenantId, UUID membershipId);
}
