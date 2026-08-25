package com.sajtech.webbff.application.model;

import java.time.Instant;
import java.util.UUID;

public record BrowserSession(
    String locator,
    BrowserSessionMode mode,
    UUID userId,
    String identitySessionId,
    UUID refreshFamilyId,
    String refreshCredential,
    UUID selectedTenantId,
    UUID selectedMembershipId,
    String csrfKeyId,
    String csrfDigestHex,
    Instant createdAt,
    Instant lastSeenAt,
    Instant idleExpiresAt,
    Instant absoluteExpiresAt,
    String mfaChallenge) {
  public BrowserSession(
      String locator,
      BrowserSessionMode mode,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      UUID selectedTenantId,
      UUID selectedMembershipId,
      String csrfKeyId,
      String csrfDigestHex,
      Instant createdAt,
      Instant lastSeenAt,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt) {
    this(
        locator,
        mode,
        userId,
        identitySessionId,
        refreshFamilyId,
        refreshCredential,
        selectedTenantId,
        selectedMembershipId,
        csrfKeyId,
        csrfDigestHex,
        createdAt,
        lastSeenAt,
        idleExpiresAt,
        absoluteExpiresAt,
        null);
  }

  public boolean authenticated() {
    return mode == BrowserSessionMode.AUTHENTICATED_ONBOARDING
        || mode == BrowserSessionMode.TENANT_AUTHENTICATED;
  }

  public boolean tenantAuthenticated() {
    return mode == BrowserSessionMode.TENANT_AUTHENTICATED;
  }
}
