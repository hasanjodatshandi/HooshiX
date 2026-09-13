package com.sajtech.conversation.infrastructure.security.keyring;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

public final class FileBackedContentKeyRing {
  private static final int KEY_BYTES = 32;
  private final Path path;
  private final Clock clock;
  private final Duration maximumStaleness;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

  public FileBackedContentKeyRing(Path path, Clock clock, Duration maximumStaleness) {
    if (path == null
        || clock == null
        || maximumStaleness == null
        || maximumStaleness.isZero()
        || maximumStaleness.isNegative()) {
      throw new IllegalArgumentException("Content key-ring configuration is invalid");
    }
    this.path = path;
    this.clock = clock;
    this.maximumStaleness = maximumStaleness;
    refresh();
  }

  public synchronized void refresh() {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to load local Conversation content key ring", exception);
    }
    String activeKeyId = required(properties, "active_key_id");
    if (!validKeyId(activeKeyId)) {
      throw new IllegalStateException("Active Conversation content key identifier is invalid");
    }
    Map<String, SecretKey> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) {
        continue;
      }
      String keyId = name.substring("key.".length());
      if (!validKeyId(keyId)) {
        throw new IllegalStateException("Conversation content key identifier is invalid");
      }
      byte[] decoded;
      try {
        String encoded = properties.getProperty(name).trim();
        decoded = Base64.getDecoder().decode(encoded);
        if (!Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
          throw new IllegalArgumentException("non-canonical");
        }
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException(
            "Conversation content key is not canonical Base64", exception);
      }
      if (decoded.length != KEY_BYTES) {
        Arrays.fill(decoded, (byte) 0);
        throw new IllegalStateException("Conversation content key has invalid length");
      }
      keys.put(keyId, new SecretKeySpec(decoded, "AES"));
      Arrays.fill(decoded, (byte) 0);
    }
    if (!keys.containsKey(activeKeyId)) {
      throw new IllegalStateException("Active Conversation content key is unavailable");
    }
    Snapshot previous = snapshot.get();
    if (previous != null) {
      rejectKeyLossOrRebinding(previous.keys(), keys);
    }
    snapshot.set(new Snapshot(activeKeyId, Map.copyOf(keys), clock.instant()));
  }

  public ContentKey activeKey() {
    Snapshot current = requireFresh();
    return new ContentKey(current.activeKeyId(), current.keys().get(current.activeKeyId()));
  }

  public SecretKey key(String keyId) {
    if (!validKeyId(keyId)) {
      throw new IllegalArgumentException("Conversation content key identifier is invalid");
    }
    SecretKey key = requireFresh().keys().get(keyId);
    if (key == null) {
      throw new IllegalStateException("Required Conversation content key is unavailable");
    }
    return key;
  }

  public boolean isFresh() {
    Snapshot current = snapshot.get();
    return current != null && clock.instant().isBefore(current.loadedAt().plus(maximumStaleness));
  }

  private Snapshot requireFresh() {
    Snapshot current = snapshot.get();
    if (current == null || !clock.instant().isBefore(current.loadedAt().plus(maximumStaleness))) {
      throw new IllegalStateException("Conversation content key-ring snapshot is stale");
    }
    return current;
  }

  private static void rejectKeyLossOrRebinding(
      Map<String, SecretKey> previous, Map<String, SecretKey> candidate) {
    for (Map.Entry<String, SecretKey> entry : previous.entrySet()) {
      SecretKey replacement = candidate.get(entry.getKey());
      if (replacement == null) {
        throw new IllegalStateException("Conversation content key cannot be removed");
      }
      byte[] left = entry.getValue().getEncoded();
      byte[] right = replacement.getEncoded();
      try {
        if (left == null || right == null || !MessageDigest.isEqual(left, right)) {
          throw new IllegalStateException("Conversation content key identifier cannot be rebound");
        }
      } finally {
        if (left != null) Arrays.fill(left, (byte) 0);
        if (right != null) Arrays.fill(right, (byte) 0);
      }
    }
  }

  private static boolean validKeyId(String value) {
    return value != null && value.matches("[A-Za-z0-9._-]{1,64}");
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Conversation content key-ring property is missing");
    }
    return value.trim();
  }

  private record Snapshot(String activeKeyId, Map<String, SecretKey> keys, Instant loadedAt) {}
}
