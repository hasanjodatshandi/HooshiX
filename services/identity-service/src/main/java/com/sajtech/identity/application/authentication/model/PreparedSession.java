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
    UUID selectedMembershipId,
    Instant mfaAuthenticatedAt,
    PrimaryAuthenticationMethod authenticationMethod) {
  public PreparedSession(
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
        mode,
        selectedTenantId,
        selectedMembershipId,
        null,
        PrimaryAuthenticationMethod.LOCAL_PASSWORD);
  }

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
        null,
        null,
        PrimaryAuthenticationMethod.LOCAL_PASSWORD);
  }

  public PreparedSession {
    if (authenticationMethod == null) {
      throw new IllegalArgumentException("Primary authentication method is required");
    }
  }
}
