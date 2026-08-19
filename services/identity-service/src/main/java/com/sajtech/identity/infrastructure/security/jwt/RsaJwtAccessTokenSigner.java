package com.sajtech.identity.infrastructure.security.jwt;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.AccessTokenContext;
import com.sajtech.identity.application.authentication.model.SignedAccessToken;
import com.sajtech.identity.application.authentication.port.out.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

public final class RsaJwtAccessTokenSigner implements AccessTokenSigner {
  private static final Duration LIFETIME = Duration.ofMinutes(5);
  private static final int MAX_TOKEN_BYTES = 4096;
  private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
  private final String issuer;
  private final FileBackedRsaSigningKeyRing keys;
  private final ObjectMapper json = new ObjectMapper();

  public RsaJwtAccessTokenSigner(String issuer, FileBackedRsaSigningKeyRing keys) {
    if (issuer == null || issuer.isBlank() || issuer.length() > 256 || hasControl(issuer)) {
      throw new IllegalArgumentException("JWT issuer configuration is invalid");
    }
    this.issuer = issuer;
    this.keys = keys;
  }

  @Override
  public SignedAccessToken sign(AccessTokenContext context) {
    requireContext(context);
    RsaSigningKeyMaterial key = keys.activeKey();
    long issuedAt = context.issuedAt().getEpochSecond();
    long expiresAt = Math.addExact(issuedAt, LIFETIME.toSeconds());
    Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", "RS256");
    header.put("kid", key.keyId());
    header.put("typ", "JWT");
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", context.audience());
    claims.put("sub", context.userId().toString());
    claims.put("jti", UUID.randomUUID().toString());
    claims.put("iat", issuedAt);
    claims.put("exp", expiresAt);
    claims.put("tenant_id", context.tenantId().toString());
    claims.put("membership_id", context.membershipId().toString());
    claims.put("sid", context.sessionId());
    try {
      String signingInput = encodeJson(header) + "." + encodeJson(claims);
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(key.privateKey());
      signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      String token = signingInput + "." + BASE64.encodeToString(signature.sign());
      if (token.getBytes(StandardCharsets.US_ASCII).length > MAX_TOKEN_BYTES) {
        throw new AuthenticationException(
            AuthenticationError.SIGNING_KEY_UNAVAILABLE, "Access token exceeds size limit");
      }
      return new SignedAccessToken(token, Instant.ofEpochSecond(expiresAt));
    } catch (GeneralSecurityException exception) {
      throw new AuthenticationException(
          AuthenticationError.SIGNING_KEY_UNAVAILABLE,
          "Access token signing is unavailable",
          exception);
    }
  }

  private String encodeJson(Map<String, Object> value) {
    try {
      return BASE64.encodeToString(json.writeValueAsBytes(value));
    } catch (RuntimeException exception) {
      throw new AuthenticationException(
          AuthenticationError.SIGNING_KEY_UNAVAILABLE,
          "Access token encoding is unavailable",
          exception);
    }
  }

  private static void requireContext(AccessTokenContext context) {
    if (context == null
        || context.userId() == null
        || context.tenantId() == null
        || context.membershipId() == null
        || context.sessionId() == null
        || context.sessionId().length() != 43
        || context.audience() == null
        || context.audience().isBlank()
        || context.audience().length() > 128
        || context.audience().contains("*")
        || hasControl(context.audience())
        || context.issuedAt() == null) {
      throw new AuthenticationException(
          AuthenticationError.SESSION_STATE_INVALID, "Access token context is invalid");
    }
  }

  private static boolean hasControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }
}
