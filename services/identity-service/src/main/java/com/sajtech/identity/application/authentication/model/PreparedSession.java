package com.sajtech.identity.application.authentication.model;

import java.time.Instant;
import java.util.UUID;

public record PreparedSession(
    UUID refreshFamilyId,
    String sessionId,
    UUID userId,
    UUID credentialId,
    RefreshDigest refreshDigest,
    Instant authenticatedAt,
    Instant createdAt,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt) {}
