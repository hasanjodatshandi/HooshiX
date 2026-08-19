package com.sajtech.identity.application.authentication.port.out;

import java.util.UUID;

public interface TenantContextValidationPort {
  boolean isSelectable(UUID userId, UUID tenantId, UUID membershipId);
}
