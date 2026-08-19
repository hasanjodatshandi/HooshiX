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
    Instant absoluteExpiresAt) {
  public boolean authenticated() {
    return mode != BrowserSessionMode.PREAUTH;
  }

  public boolean tenantAuthenticated() {
    return mode == BrowserSessionMode.TENANT_AUTHENTICATED;
  }
}
