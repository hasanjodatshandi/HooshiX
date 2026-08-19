package com.sajtech.identity.application.tenant.model;

import java.util.UUID;

public record AuthorizationOutboxItem(
    UUID outboxId,
    UUID requestId,
    String operation,
    UUID tenantId,
    UUID membershipId,
    UUID userId,
    String lifecycle,
    int attemptCount) {}
