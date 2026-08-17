package com.sajtech.notification.infrastructure.security.keyring;

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
      throw new IllegalStateException("Unable to load local notification key ring", exception);
    }

    String activeKeyId = required(properties, "active_key_id");
    Map<String, SecretKey> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) {
        continue;
      }
      String keyId = name.substring("key.".length());
      if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) {
        throw new IllegalStateException("Notification key identifier is invalid");
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(properties.getProperty(name).trim());
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "Notification key material is not canonical Base64", exception);
      }
      if (decoded.length != expectedKeyBytes) {
        throw new IllegalStateException("Notification key material has invalid length");
      }
      keys.put(keyId, new SecretKeySpec(decoded, algorithm));
      java.util.Arrays.fill(decoded, (byte) 0);
    }
    if (!keys.containsKey(activeKeyId)) {
      throw new IllegalStateException("Active notification key identifier is unavailable");
    }
    snapshot.set(new Snapshot(activeKeyId, Map.copyOf(keys), clock.instant()));
  }

  public KeyRingMaterial activeKey() {
    Snapshot current = requireFreshSnapshot();
    return new KeyRingMaterial(current.activeKeyId(), current.keys().get(current.activeKeyId()));
  }

  public SecretKey key(String keyId) {
    Snapshot current = requireFreshSnapshot();
    SecretKey key = current.keys().get(keyId);
    if (key == null) {
      throw new IllegalStateException("Required notification verification key is unavailable");
    }
    return key;
  }

  public boolean isFresh() {
    Snapshot current = snapshot.get();
    return current != null && !current.loadedAt().plus(maximumStaleness).isBefore(clock.instant());
  }

  private Snapshot requireFreshSnapshot() {
    Snapshot current = snapshot.get();
    if (current == null || current.loadedAt().plus(maximumStaleness).isBefore(clock.instant())) {
      throw new IllegalStateException("Notification key-ring snapshot is stale");
    }
    return current;
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Notification key-ring property is missing");
    }
    return value.trim();
  }

  private record Snapshot(String activeKeyId, Map<String, SecretKey> keys, Instant loadedAt) {}
}
