package com.sajtech.identity.application.authentication.model;

import java.time.Instant;
import java.util.UUID;

public record AccessTokenContext(
    UUID userId,
    UUID tenantId,
    UUID membershipId,
    String sessionId,
    String audience,
    Instant issuedAt) {}
