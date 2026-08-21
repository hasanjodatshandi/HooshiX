package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record SelectableTenant(UUID tenantId, UUID membershipId, String name, String slug) {}
