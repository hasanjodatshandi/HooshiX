package com.sajtech.notification.infrastructure.security.keyring;

import java.io.IOException;
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

public final class FileBackedKeyRing {
  private static final String ACTIVE_FINGERPRINT_KEY_ID = "fingerprint.active-key-id";
  private static final String ACTIVE_DELIVERY_KEY_ID = "delivery.active-key-id";

  private final Path directory;
  private final Duration maxStaleness;
  private final Clock clock;
  private final AtomicReference<KeyRingSnapshot> snapshot = new AtomicReference<>();

  public FileBackedKeyRing(Path directory, Duration maxStaleness) {
    this(directory, maxStaleness, Clock.systemUTC());
  }

  FileBackedKeyRing(Path directory, Duration maxStaleness, Clock clock) {
    this.directory = directory;
    this.maxStaleness = maxStaleness;
    this.clock = clock;
    reload();
  }

  public void reload() {
    Path propertiesPath = directory.resolve("key-ring.properties");
    Properties properties = new Properties();
    try (var reader = Files.newBufferedReader(propertiesPath)) {
      properties.load(reader);
    } catch (IOException readFailure) {
      throw new IllegalStateException("Notification key ring could not be read", readFailure);
    }
    String activeFingerprintKeyId = required(properties, ACTIVE_FINGERPRINT_KEY_ID);
    String activeDeliveryKeyId = required(properties, ACTIVE_DELIVERY_KEY_ID);
    Map<String, FingerprintKey> fingerprintKeys = new HashMap<>();
    Map<String, DeliveryEncryptionKey> deliveryKeys = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (name.startsWith("fingerprint.key.")) {
        String keyId = name.substring("fingerprint.key.".length());
        fingerprintKeys.put(
            keyId,
            new FingerprintKey(keyId, decode(required(properties, name), 32, "fingerprint")));
      }
      if (name.startsWith("delivery.key.")) {
        String keyId = name.substring("delivery.key.".length());
        deliveryKeys.put(
            keyId,
            new DeliveryEncryptionKey(keyId, decode(required(properties, name), 32, "delivery")));
      }
    }
    if (!fingerprintKeys.containsKey(activeFingerprintKeyId)) {
      throw new IllegalStateException("Active notification fingerprint key is missing");
    }
    if (!deliveryKeys.containsKey(activeDeliveryKeyId)) {
      throw new IllegalStateException("Active notification delivery key is missing");
    }
    snapshot.set(
        new KeyRingSnapshot(
            Map.copyOf(fingerprintKeys),
            Map.copyOf(deliveryKeys),
            activeFingerprintKeyId,
            activeDeliveryKeyId,
            clock.instant()));
  }

  public boolean isFresh() {
    KeyRingSnapshot current = snapshot.get();
    return current != null
        && Duration.between(current.loadedAt(), clock.instant()).compareTo(maxStaleness) <= 0;
  }

  public FingerprintKey activeFingerprintKey() {
    requireFresh();
    KeyRingSnapshot current = snapshot.get();
    return current.fingerprintKeys().get(current.activeFingerprintKeyId());
  }

  public FingerprintKey fingerprintKey(String keyId) {
    requireFresh();
    FingerprintKey key = snapshot.get().fingerprintKeys().get(keyId);
    if (key == null) {
      throw new IllegalStateException("Notification fingerprint key is unavailable");
    }
    return key;
  }

  public DeliveryEncryptionKey activeDeliveryKey() {
    requireFresh();
    KeyRingSnapshot current = snapshot.get();
    return current.deliveryKeys().get(current.activeDeliveryKeyId());
  }

  private void requireFresh() {
    if (!isFresh()) {
      throw new IllegalStateException("Notification key ring is stale");
    }
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Notification key-ring setting is missing");
    }
    return value.trim();
  }

  private static byte[] decode(String encoded, int requiredBytes, String usage) {
    try {
      byte[] decoded = Base64.getDecoder().decode(encoded);
      if (decoded.length != requiredBytes) {
        throw new IllegalStateException("Notification " + usage + " key has invalid length");
      }
      return decoded;
    } catch (IllegalArgumentException invalidBase64) {
      throw new IllegalStateException(
          "Notification " + usage + " key is not valid base64", invalidBase64);
    }
  }

  private record KeyRingSnapshot(
      Map<String, FingerprintKey> fingerprintKeys,
      Map<String, DeliveryEncryptionKey> deliveryKeys,
      String activeFingerprintKeyId,
      String activeDeliveryKeyId,
      Instant loadedAt) {}
}
