package com.sajtech.webbff.application.port.out;

import com.sajtech.webbff.application.model.*;
import java.time.Instant;
import java.util.UUID;

public interface BrowserSessionPort {
  BrowserSessionGrant bootstrap();

  BrowserSessionGrant rotateMfaPreauth(
      BrowserSession old, UUID userId, String mfaChallenge, Instant challengeExpiresAt);

  BrowserSessionGrant rotateAuthenticated(
      BrowserSession old,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant identityIdle,
      Instant identityAbsolute);

  BrowserSessionGrant rotateAuthenticatedTenant(
      BrowserSession old,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant identityIdle,
      Instant identityAbsolute,
      UUID tenantId,
      UUID membershipId);

  BrowserSessionGrant rotateTenant(
      BrowserSession old,
      String rotatedRefresh,
      Instant identityIdle,
      Instant identityAbsolute,
      UUID tenantId,
      UUID membershipId);

  BrowserSessionGrant rotateSecurityState(
      BrowserSession old, String rotatedRefresh, Instant identityIdle, Instant identityAbsolute);

  void destroy(BrowserSession session);

  void eraseUser(UUID userId);
}
