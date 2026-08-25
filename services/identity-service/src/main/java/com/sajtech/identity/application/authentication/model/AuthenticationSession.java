package com.sajtech.identity.application.authentication.model;

import java.time.Instant;
import java.util.UUID;

public record AuthenticationSession(
    String sessionId,
    UUID refreshFamilyId,
    UUID userId,
    String refreshCredential,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    AuthenticationSessionMode mode,
    UUID selectedTenantId,
    UUID selectedMembershipId,
    String mfaChallenge) {
  public AuthenticationSession(
      String sessionId,
      UUID refreshFamilyId,
      UUID userId,
      String refreshCredential,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      AuthenticationSessionMode mode) {
    this(
        sessionId,
        refreshFamilyId,
        userId,
        refreshCredential,
        idleExpiresAt,
        absoluteExpiresAt,
        mode,
        null,
        null,
        null);
  }

  public AuthenticationSession(
      String sessionId,
      UUID refreshFamilyId,
      UUID userId,
      String refreshCredential,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      AuthenticationSessionMode mode,
      UUID selectedTenantId,
      UUID selectedMembershipId) {
    this(
        sessionId,
        refreshFamilyId,
        userId,
        refreshCredential,
        idleExpiresAt,
        absoluteExpiresAt,
        mode,
        selectedTenantId,
        selectedMembershipId,
        null);
  }

  public static AuthenticationSession mfaRequired(UUID userId, String challenge) {
    if (userId == null || challenge == null || !challenge.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("MFA authentication result is invalid");
    }
    return new AuthenticationSession(
        null,
        null,
        userId,
        null,
        null,
        null,
        AuthenticationSessionMode.MFA_REQUIRED,
        null,
        null,
        challenge);
  }
}
