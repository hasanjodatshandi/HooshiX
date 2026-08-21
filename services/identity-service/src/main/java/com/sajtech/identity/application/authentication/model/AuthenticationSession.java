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
    UUID selectedMembershipId) {
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
        null);
  }
}
