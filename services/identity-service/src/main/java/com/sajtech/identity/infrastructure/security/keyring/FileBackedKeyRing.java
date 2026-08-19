package com.sajtech.identity.infrastructure.security.keyring;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class FileBackedKeyRing {
  private final Path path;
  private final String algorithm;
  private final int expectedKeyBytes;
  private final Clock clock;
  private final Duration maximumStaleness;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

  public FileBackedKeyRing(
      Path path, String algorithm, int expectedKeyBytes, Clock clock, Duration maximumStaleness) {
    if (path == null
        || algorithm == null
        || algorithm.isBlank()
        || expectedKeyBytes <= 0
        || clock == null
        || maximumStaleness == null
        || maximumStaleness.isNegative()
        || maximumStaleness.isZero()) {
      throw new IllegalArgumentException("Key-ring configuration is invalid");
    }
    this.path = path;
    this.algorithm = algorithm;
    this.expectedKeyBytes = expectedKeyBytes;
    this.clock = clock;
    this.maximumStaleness = maximumStaleness;
    refresh();
  }

  public synchronized void refresh() {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load local Identity key ring", exception);
    }
    String active = required(properties, "active_key_id");
    Map<String, SecretKey> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) continue;
      String keyId = name.substring(4);
      if (!keyId.matches("[A-Za-z0-9._-]{1,64}"))
        throw new IllegalStateException("Identity key identifier is invalid");
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(properties.getProperty(name).trim());
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("Identity key material is not canonical Base64", exception);
      }
      if (decoded.length != expectedKeyBytes)
        throw new IllegalStateException("Identity key material has invalid length");
      keys.put(keyId, new SecretKeySpec(decoded, algorithm));
      java.util.Arrays.fill(decoded, (byte) 0);
    }
    if (!keys.containsKey(active))
      throw new IllegalStateException("Active Identity key identifier is unavailable");
    snapshot.set(new Snapshot(active, Map.copyOf(keys), clock.instant()));
  }

  public KeyRingMaterial activeKey() {
    Snapshot s = fresh();
    return new KeyRingMaterial(s.activeKeyId(), s.keys().get(s.activeKeyId()));
  }

  public SecretKey key(String keyId) {
    Snapshot s = fresh();
    SecretKey key = s.keys().get(keyId);
    if (key == null)
      throw new IllegalStateException("Required Identity verification key is unavailable");
    return key;
  }

  public boolean isFresh() {
    Snapshot s = snapshot.get();
    return s != null && !s.loadedAt().plus(maximumStaleness).isBefore(clock.instant());
  }

  private Snapshot fresh() {
    Snapshot s = snapshot.get();
    if (s == null || s.loadedAt().plus(maximumStaleness).isBefore(clock.instant()))
      throw new IllegalStateException("Identity key-ring snapshot is stale");
    return s;
  }

  private static String required(Properties p, String name) {
    String v = p.getProperty(name);
    if (v == null || v.isBlank())
      throw new IllegalStateException("Identity key-ring property is missing");
    return v.trim();
  }

  private record Snapshot(String activeKeyId, Map<String, SecretKey> keys, Instant loadedAt) {}
}
