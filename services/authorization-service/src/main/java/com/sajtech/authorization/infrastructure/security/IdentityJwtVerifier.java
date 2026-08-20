package com.sajtech.authorization.infrastructure.security;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.ActorContext;
import com.sajtech.authorization.application.port.out.AccessTokenVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class IdentityJwtVerifier implements AccessTokenVerifier {
  private static final Base64.Decoder B64 = Base64.getUrlDecoder();
  private static final int MAX_TOKEN_BYTES = 4096;
  private final Path bundlePath;
  private final String issuer;
  private final Clock clock;
  private final Duration maximumStaleness;
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

  public IdentityJwtVerifier(
      Path bundlePath, String issuer, Clock clock, Duration maximumStaleness) {
    if (bundlePath == null
        || issuer == null
        || issuer.isBlank()
        || clock == null
        || maximumStaleness == null
        || maximumStaleness.isNegative()
        || maximumStaleness.isZero())
      throw new IllegalArgumentException("JWT verifier configuration is invalid");
    this.bundlePath = bundlePath;
    this.issuer = issuer;
    this.clock = clock;
    this.maximumStaleness = maximumStaleness;
    refresh();
  }

  public synchronized void refresh() {
    Properties p = new Properties();
    try (Reader r = Files.newBufferedReader(bundlePath, StandardCharsets.UTF_8)) {
      p.load(r);
    } catch (IOException e) {
      throw unavailable(e);
    }
    String current = keyId(required(p, "current_key_id"));
    Set<String> refs = new HashSet<>();
    refs.add(current);
    optional(p, "next_key_id")
        .ifPresent(
            v -> {
              if (!refs.add(keyId(v))) throw unavailable(null);
            });
    optional(p, "previous_key_id")
        .ifPresent(
            v -> {
              if (!refs.add(keyId(v))) throw unavailable(null);
            });
    Map<String, RSAPublicKey> keys = new HashMap<>();
    for (String name : p.stringPropertyNames())
      if (name.startsWith("key.")) {
        String id = keyId(name.substring(4));
        if (!refs.contains(id)) throw unavailable(null);
        keys.put(id, publicKey(p.getProperty(name)));
      }
    if (keys.size() > 3 || !keys.keySet().equals(refs)) throw unavailable(null);
    snapshot.set(new Snapshot(Map.copyOf(keys), clock.instant()));
  }

  public boolean isFresh() {
    Snapshot s = snapshot.get();
    return s != null && !s.loadedAt().plus(maximumStaleness).isBefore(clock.instant());
  }

  @Override
  public ActorContext verify(String token) {
    if (token == null
        || token.isBlank()
        || token.getBytes(StandardCharsets.US_ASCII).length > MAX_TOKEN_BYTES) throw invalid();
    Snapshot s = snapshot.get();
    if (s == null || s.loadedAt().plus(maximumStaleness).isBefore(clock.instant()))
      throw unavailable(null);
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isBlank)) throw invalid();
    try {
      JsonNode header = json.readTree(B64.decode(parts[0]));
      JsonNode claims = json.readTree(B64.decode(parts[1]));
      if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ")))
        throw invalid();
      String kid = keyId(text(header, "kid"));
      RSAPublicKey key = s.keys().get(kid);
      if (key == null) throw invalid();
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      byte[] signature = B64.decode(parts[2]);
      try {
        if (!verifier.verify(signature)) throw invalid();
      } finally {
        Arrays.fill(signature, (byte) 0);
      }
      if (!issuer.equals(text(claims, "iss"))
          || !"authorization-service".equals(text(claims, "aud"))) throw invalid();
      if (claims.has("roles") || claims.has("permissions") || claims.has("authorization_version"))
        throw invalid();
      UUID user = uuid(text(claims, "sub"));
      UUID tenant = uuid(text(claims, "tenant_id"));
      UUID membership = uuid(text(claims, "membership_id"));
      String sid = text(claims, "sid");
      uuid(text(claims, "jti"));
      if (sid.length() != 43 || sid.codePoints().anyMatch(Character::isISOControl)) throw invalid();
      long iat = number(claims, "iat"), exp = number(claims, "exp");
      long now = clock.instant().getEpochSecond();
      if (exp <= iat || exp - iat > 300 || iat > now + 30 || exp < now - 30) throw invalid();
      return new ActorContext(user, tenant, membership, sid);
    } catch (AuthorizationException e) {
      throw e;
    } catch (java.security.GeneralSecurityException e) {
      throw invalid();
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) throw invalid();
    return v.asString();
  }

  private static long number(JsonNode n, String field) {
    JsonNode v = n.get(field);
    if (v == null || !v.isIntegralNumber()) throw invalid();
    return v.asLong();
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw invalid();
    }
  }

  private static RSAPublicKey publicKey(String encoded) {
    try {
      byte[] der = Base64.getDecoder().decode(encoded.trim());
      try {
        RSAPublicKey key =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        if (key.getModulus().bitLength() != 3072) throw unavailable(null);
        return key;
      } finally {
        Arrays.fill(der, (byte) 0);
      }
    } catch (Exception e) {
      throw unavailable(e);
    }
  }

  private static String keyId(String v) {
    if (v == null || !v.matches("[A-Za-z0-9._-]{1,64}")) throw unavailable(null);
    return v;
  }

  private static String required(Properties p, String k) {
    String v = p.getProperty(k);
    if (v == null || v.isBlank()) throw unavailable(null);
    return v.trim();
  }

  private static Optional<String> optional(Properties p, String k) {
    String v = p.getProperty(k);
    return v == null || v.isBlank() ? Optional.empty() : Optional.of(v.trim());
  }

  private static AuthorizationException invalid() {
    return new AuthorizationException(
        AuthorizationError.INVALID_ACCESS_TOKEN, "Access token is invalid");
  }

  private static AuthorizationException unavailable(Throwable cause) {
    return cause == null
        ? new AuthorizationException(
            AuthorizationError.AUTHORIZATION_UNAVAILABLE, "JWT verifier bundle is unavailable")
        : new AuthorizationException(
            AuthorizationError.AUTHORIZATION_UNAVAILABLE,
            "JWT verifier bundle is unavailable",
            cause);
  }

  private record Snapshot(Map<String, RSAPublicKey> keys, Instant loadedAt) {}
}
