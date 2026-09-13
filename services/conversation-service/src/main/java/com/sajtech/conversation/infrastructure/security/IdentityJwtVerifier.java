package com.sajtech.conversation.infrastructure.security;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.port.out.AccessTokenVerifier;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class IdentityJwtVerifier implements AccessTokenVerifier {
  private static final Base64.Decoder BASE64_URL = Base64.getUrlDecoder();
  private static final int MAX_TOKEN_BYTES = 4096;
  private static final String AUDIENCE = "conversation-service";
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
        || issuer.length() > 256
        || issuer.codePoints().anyMatch(Character::isISOControl)
        || clock == null
        || maximumStaleness == null
        || maximumStaleness.isNegative()
        || maximumStaleness.isZero()) {
      throw new IllegalArgumentException("JWT verifier configuration is invalid");
    }
    this.bundlePath = bundlePath;
    this.issuer = issuer;
    this.clock = clock;
    this.maximumStaleness = maximumStaleness;
    refresh();
  }

  public synchronized void refresh() {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(bundlePath, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw unavailable(exception);
    }
    String current = keyId(required(properties, "current_key_id"));
    Set<String> references = new HashSet<>();
    references.add(current);
    optional(properties, "next_key_id")
        .ifPresent(
            value -> {
              if (!references.add(keyId(value))) throw unavailable(null);
            });
    optional(properties, "previous_key_id")
        .ifPresent(
            value -> {
              if (!references.add(keyId(value))) throw unavailable(null);
            });
    Map<String, RSAPublicKey> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith("key.")) {
        String id = keyId(name.substring(4));
        if (!references.contains(id)) throw unavailable(null);
        keys.put(id, publicKey(properties.getProperty(name)));
      }
    }
    if (keys.size() > 3 || !keys.keySet().equals(references)) throw unavailable(null);
    Snapshot previous = snapshot.get();
    if (previous != null) requireNoKeyIdRebinding(previous.keys(), keys);
    snapshot.set(new Snapshot(Map.copyOf(keys), clock.instant()));
  }

  public boolean isFresh() {
    Snapshot value = snapshot.get();
    return value != null && !value.loadedAt().plus(maximumStaleness).isBefore(clock.instant());
  }

  @Override
  public ConversationActor verify(String token) {
    if (token == null
        || token.isBlank()
        || token.getBytes(StandardCharsets.US_ASCII).length > MAX_TOKEN_BYTES) throw invalid();
    Snapshot value = snapshot.get();
    if (value == null || value.loadedAt().plus(maximumStaleness).isBefore(clock.instant())) {
      throw unavailable(null);
    }
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || Arrays.stream(parts).anyMatch(String::isBlank)) throw invalid();
    try {
      JsonNode header = json.readTree(BASE64_URL.decode(parts[0]));
      JsonNode claims = json.readTree(BASE64_URL.decode(parts[1]));
      if (!"RS256".equals(text(header, "alg")) || !"JWT".equals(text(header, "typ"))) {
        throw invalid();
      }
      RSAPublicKey key = value.keys().get(keyId(text(header, "kid")));
      if (key == null) throw invalid();
      Signature verifier = Signature.getInstance("SHA256withRSA");
      verifier.initVerify(key);
      verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
      byte[] signature = BASE64_URL.decode(parts[2]);
      try {
        if (!verifier.verify(signature)) throw invalid();
      } finally {
        Arrays.fill(signature, (byte) 0);
      }
      if (!issuer.equals(text(claims, "iss")) || !AUDIENCE.equals(text(claims, "aud"))) {
        throw invalid();
      }
      if (claims.has("roles") || claims.has("permissions") || claims.has("authorization_version")) {
        throw invalid();
      }
      UUID user = uuid(text(claims, "sub"));
      UUID tenant = uuid(text(claims, "tenant_id"));
      UUID membership = uuid(text(claims, "membership_id"));
      String session = text(claims, "sid");
      uuid(text(claims, "jti"));
      if (session.length() != 43 || session.codePoints().anyMatch(Character::isISOControl)) {
        throw invalid();
      }
      long issuedAt = number(claims, "iat");
      long expiresAt = number(claims, "exp");
      long now = clock.instant().getEpochSecond();
      if (expiresAt <= issuedAt
          || expiresAt - issuedAt > 300
          || issuedAt > now + 30
          || expiresAt < now - 30) {
        throw invalid();
      }
      return new ConversationActor(user, tenant, membership, session);
    } catch (ConversationException exception) {
      throw exception;
    } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
      throw invalid();
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
    return value.asString();
  }

  private static long number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber()) throw invalid();
    return value.asLong();
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
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
    } catch (Exception exception) {
      throw unavailable(exception);
    }
  }

  private static void requireNoKeyIdRebinding(
      Map<String, RSAPublicKey> previous, Map<String, RSAPublicKey> candidate) {
    for (Map.Entry<String, RSAPublicKey> entry : previous.entrySet()) {
      RSAPublicKey replacement = candidate.get(entry.getKey());
      if (replacement != null && !sameKey(entry.getValue(), replacement)) {
        throw unavailable(null);
      }
    }
  }

  private static boolean sameKey(RSAPublicKey left, RSAPublicKey right) {
    byte[] leftBytes = left.getEncoded();
    byte[] rightBytes = right.getEncoded();
    try {
      return leftBytes != null
          && rightBytes != null
          && MessageDigest.isEqual(leftBytes, rightBytes);
    } finally {
      if (leftBytes != null) Arrays.fill(leftBytes, (byte) 0);
      if (rightBytes != null) Arrays.fill(rightBytes, (byte) 0);
    }
  }

  private static String keyId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) throw unavailable(null);
    return value;
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) throw unavailable(null);
    return value.trim();
  }

  private static Optional<String> optional(Properties properties, String key) {
    String value = properties.getProperty(key);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  private static ConversationException invalid() {
    return new ConversationException(
        ConversationError.INVALID_ACCESS_TOKEN, "Access token is invalid");
  }

  private static ConversationException unavailable(Throwable cause) {
    return cause == null
        ? new ConversationException(
            ConversationError.AUTHORIZATION_UNAVAILABLE, "JWT verifier bundle is unavailable")
        : new ConversationException(
            ConversationError.AUTHORIZATION_UNAVAILABLE,
            "JWT verifier bundle is unavailable",
            cause);
  }

  private record Snapshot(Map<String, RSAPublicKey> keys, Instant loadedAt) {}
}
