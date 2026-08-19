package com.sajtech.identity.application.authentication.model;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationSession(
    String sessionId,
    UUID refreshFamilyId,
    String refreshCredential,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    AuthenticationSessionMode mode) {}
