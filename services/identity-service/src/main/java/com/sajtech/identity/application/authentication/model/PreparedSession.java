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
    Instant absoluteExpiresAt,
    AuthenticationSessionMode mode,
    UUID selectedTenantId,
    UUID selectedMembershipId) {
  public PreparedSession(
      UUID refreshFamilyId,
      String sessionId,
      UUID userId,
      UUID credentialId,
      RefreshDigest refreshDigest,
      Instant authenticatedAt,
      Instant createdAt,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt) {
    this(
        refreshFamilyId,
        sessionId,
        userId,
        credentialId,
        refreshDigest,
        authenticatedAt,
        createdAt,
        idleExpiresAt,
        absoluteExpiresAt,
        AuthenticationSessionMode.AUTHENTICATED_ONBOARDING,
        null,
        null);
  }
}
