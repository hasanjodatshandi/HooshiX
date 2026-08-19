package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record TenantCreation(UUID tenantId, UUID membershipId, String lifecycle) {}
