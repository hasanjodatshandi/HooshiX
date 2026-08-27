package com.sajtech.identity.application.tenant.model;

import java.time.Instant;
import java.util.UUID;

public record InvitationSummary(
    UUID invitationId,
    UUID tenantId,
    String tenantName,
    String tenantSlug,
    String state,
    Instant expiresAt) {}
