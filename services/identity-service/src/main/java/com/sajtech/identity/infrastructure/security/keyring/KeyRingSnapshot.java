package com.sajtech.identity.infrastructure.security.keyring;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public final class KeyRingSnapshot {
  private final String activeKeyId;
  private final Map<String, byte[]> keys;
  private final Instant loadedAt;

  public KeyRingSnapshot(String activeKeyId, Map<String, byte[]> keys, Instant loadedAt) {
    this.activeKeyId = Objects.requireNonNull(activeKeyId, "activeKeyId");
    this.keys =
        Map.copyOf(
            keys.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> Arrays.copyOf(entry.getValue(), entry.getValue().length))));
    this.loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
    if (!this.keys.containsKey(activeKeyId)) {
      throw new IllegalArgumentException("active key is missing");
    }
  }

  public String activeKeyId() {
    return activeKeyId;
  }

  public byte[] activeKey() {
    return key(activeKeyId);
  }

  public byte[] key(String keyId) {
    byte[] key = keys.get(keyId);
    if (key == null) {
      throw new IllegalArgumentException("unknown key id");
    }
    return Arrays.copyOf(key, key.length);
  }

  public Instant loadedAt() {
    return loadedAt;
  }
}
