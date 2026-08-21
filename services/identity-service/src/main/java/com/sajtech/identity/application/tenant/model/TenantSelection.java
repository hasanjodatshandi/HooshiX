package com.sajtech.identity.application.tenant.model;

import com.sajtech.identity.application.authentication.model.SignedAccessToken;
import java.time.Instant;
import java.util.UUID;

public record TenantSelection(
    String sessionId,
    UUID refreshFamilyId,
    String refreshCredential,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    UUID tenantId,
    UUID membershipId,
    SignedAccessToken accessToken) {}
