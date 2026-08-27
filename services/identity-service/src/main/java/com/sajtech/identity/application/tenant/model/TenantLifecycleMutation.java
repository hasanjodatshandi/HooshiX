package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record TenantLifecycleMutation(
    UUID tenantId, String lifecycle, String targetLifecycle, boolean pending) {}
