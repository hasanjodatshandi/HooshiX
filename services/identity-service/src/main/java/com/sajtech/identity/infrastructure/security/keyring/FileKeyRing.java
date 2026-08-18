package com.sajtech.identity.infrastructure.security.keyring;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

public final class FileKeyRing {
  private static final int KEY_BYTES = 32;
  private static final int MAX_KEYS = 16;

  private final Path path;
  private final Clock clock;
  private final Duration maximumStaleness;
  private final AtomicReference<KeyRingSnapshot> snapshot = new AtomicReference<>();

  public FileKeyRing(Path path, Clock clock, Duration maximumStaleness) {
    this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
    this.maximumStaleness = Objects.requireNonNull(maximumStaleness, "maximumStaleness");
    refreshRequired();
  }

  public KeyRingSnapshot snapshot() {
    KeyRingSnapshot current = snapshot.get();
    if (current == null) {
      throw new IllegalStateException("key ring is unavailable");
    }
    return current;
  }

  public boolean refreshKeepingValidSnapshot() {
    try {
      snapshot.set(load());
      return true;
    } catch (RuntimeException exception) {
      return snapshot.get() != null;
    }
  }

  public boolean ready() {
    KeyRingSnapshot current = snapshot.get();
    return current != null
        && !clock.instant().isAfter(current.loadedAt().plus(maximumStaleness));
  }

  private void refreshRequired() {
    snapshot.set(load());
  }

  private KeyRingSnapshot load() {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException exception) {
      throw new IllegalStateException("key ring cannot be read", exception);
    }
    String activeKeyId = require(properties, "active_key_id");
    Map<String, byte[]> keys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) {
        continue;
      }
      String keyId = name.substring("key.".length());
      if (keyId.isBlank() || keys.size() >= MAX_KEYS) {
        throw new IllegalStateException("invalid key ring key set");
      }
      byte[] decoded;
      try {
        decoded = Base64.getDecoder().decode(properties.getProperty(name).trim());
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("invalid key ring encoding", exception);
      }
      if (decoded.length != KEY_BYTES) {
        throw new IllegalStateException("key ring keys must be 256-bit");
      }
      keys.put(keyId, decoded);
    }
    if (keys.isEmpty() || !keys.containsKey(activeKeyId)) {
      throw new IllegalStateException("active key is unavailable");
    }
    return new KeyRingSnapshot(activeKeyId, keys, clock.instant());
  }

  private static String require(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("required key ring property is missing");
    }
    return value.trim();
  }
}
