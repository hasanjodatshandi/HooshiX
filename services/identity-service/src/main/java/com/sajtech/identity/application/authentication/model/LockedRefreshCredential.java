package com.sajtech.identity.application.authentication.model;

import java.time.Instant;
import java.util.UUID;

public record LockedRefreshCredential(
    UUID credentialId,
    UUID refreshFamilyId,
    String sessionId,
    UUID userId,
    String credentialState,
    String familyState,
    String userStatus,
    AuthenticationSessionMode sessionMode,
    UUID selectedTenantId,
    UUID selectedMembershipId,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt) {
  public LockedRefreshCredential(
      UUID credentialId,
      UUID refreshFamilyId,
      String sessionId,
      UUID userId,
      String credentialState,
      String familyState,
      String userStatus,
      AuthenticationSessionMode sessionMode,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt) {
    this(
        credentialId,
        refreshFamilyId,
        sessionId,
        userId,
        credentialState,
        familyState,
        userStatus,
        sessionMode,
        null,
        null,
        idleExpiresAt,
        absoluteExpiresAt);
  }
}
