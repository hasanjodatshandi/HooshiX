package com.sajtech.identity.infrastructure.security.jwt;

import java.io.IOException;
import java.io.Reader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class FileBackedRsaSigningKeyRing {
  private static final int RSA_BITS = 3072;
  private static final int MAX_PRIVATE_KEYS = 3;
  private static final int MAX_PUBLIC_KEYS = 3;
  private final Path privatePath;
  private final Path publicPath;
  private final Clock clock;
  private final Duration maximumStaleness;
  private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();

  public FileBackedRsaSigningKeyRing(
      Path privatePath, Path publicPath, Clock clock, Duration maximumStaleness) {
    if (privatePath == null
        || publicPath == null
        || clock == null
        || maximumStaleness == null
        || maximumStaleness.isZero()
        || maximumStaleness.isNegative()) {
      throw new IllegalArgumentException("JWT signing key-ring configuration is invalid");
    }
    this.privatePath = privatePath;
    this.publicPath = publicPath;
    this.clock = clock;
    this.maximumStaleness = maximumStaleness;
    refresh();
  }

  public synchronized void refresh() {
    Properties privateProperties = load(privatePath);
    Properties publicProperties = load(publicPath);
    String activeKeyId = keyId(required(privateProperties, "active_key_id"));
    String currentKeyId = keyId(required(publicProperties, "current_key_id"));
    if (!activeKeyId.equals(currentKeyId)) {
      throw new IllegalStateException("Active JWT signing key is not current in verifier bundle");
    }

    Map<String, RSAPrivateKey> privateKeys = privateKeys(privateProperties);
    PublicBundle publicBundle = publicKeys(publicProperties);
    if (privateKeys.size() > MAX_PRIVATE_KEYS || publicBundle.keys().size() > MAX_PUBLIC_KEYS) {
      throw new IllegalStateException("JWT key-ring cardinality is invalid");
    }
    RSAPrivateKey privateKey = privateKeys.get(activeKeyId);
    RSAPublicKey publicKey = publicBundle.keys().get(currentKeyId);
    if (privateKey == null || publicKey == null || !matches(privateKey, publicKey)) {
      throw new IllegalStateException("Active JWT signing key does not match verifier bundle");
    }
    Snapshot previous = snapshot.get();
    if (previous != null) {
      requireNoKeyIdRebinding(previous.privateKeys(), privateKeys, "private");
      requireNoKeyIdRebinding(previous.publicKeys(), publicBundle.keys(), "public");
    }
    snapshot.set(
        new Snapshot(
            new RsaSigningKeyMaterial(activeKeyId, privateKey, publicKey),
            privateKeys,
            publicBundle.keys(),
            clock.instant()));
  }

  public RsaSigningKeyMaterial activeKey() {
    return fresh().active();
  }

  public boolean isFresh() {
    Snapshot current = snapshot.get();
    return current != null && !current.loadedAt().plus(maximumStaleness).isBefore(clock.instant());
  }

  private Snapshot fresh() {
    Snapshot current = snapshot.get();
    if (current == null || current.loadedAt().plus(maximumStaleness).isBefore(clock.instant())) {
      throw new IllegalStateException("JWT signing key-ring snapshot is stale");
    }
    return current;
  }

  private static Map<String, RSAPrivateKey> privateKeys(Properties properties) {
    Map<String, RSAPrivateKey> result = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) continue;
      String id = keyId(name.substring(4));
      result.put(id, parsePrivate(properties.getProperty(name)));
    }
    if (result.isEmpty()) throw new IllegalStateException("JWT private key ring is empty");
    return Map.copyOf(result);
  }

  private static PublicBundle publicKeys(Properties properties) {
    String current = keyId(required(properties, "current_key_id"));
    Set<String> referenced = new HashSet<>();
    referenced.add(current);
    optionalKeyId(properties, "next_key_id")
        .ifPresent(
            value -> {
              if (!referenced.add(value)) {
                throw new IllegalStateException("JWT verifier bundle key roles must be distinct");
              }
            });
    optionalKeyId(properties, "previous_key_id")
        .ifPresent(
            value -> {
              if (!referenced.add(value)) {
                throw new IllegalStateException("JWT verifier bundle key roles must be distinct");
              }
            });
    Map<String, RSAPublicKey> result = new HashMap<>();
    for (String name : properties.stringPropertyNames()) {
      if (!name.startsWith("key.")) continue;
      String id = keyId(name.substring(4));
      if (!referenced.contains(id)) {
        throw new IllegalStateException("JWT verifier bundle contains an unreferenced key");
      }
      result.put(id, parsePublic(properties.getProperty(name)));
    }
    if (!result.keySet().equals(referenced)) {
      throw new IllegalStateException("JWT verifier bundle key references are incomplete");
    }
    return new PublicBundle(Map.copyOf(result));
  }

  private static java.util.Optional<String> optionalKeyId(Properties properties, String name) {
    String value = properties.getProperty(name);
    return value == null || value.isBlank()
        ? java.util.Optional.empty()
        : java.util.Optional.of(keyId(value.trim()));
  }

  private static RSAPrivateKey parsePrivate(String encoded) {
    try {
      byte[] der = Base64.getDecoder().decode(encoded.trim());
      try {
        RSAPrivateKey key =
            (RSAPrivateKey)
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        require3072(key.getModulus());
        return key;
      } finally {
        java.util.Arrays.fill(der, (byte) 0);
      }
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("JWT private key material is invalid", exception);
    }
  }

  private static RSAPublicKey parsePublic(String encoded) {
    try {
      byte[] der = Base64.getDecoder().decode(encoded.trim());
      try {
        RSAPublicKey key =
            (RSAPublicKey)
                KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        require3072(key.getModulus());
        return key;
      } finally {
        java.util.Arrays.fill(der, (byte) 0);
      }
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalStateException("JWT public key material is invalid", exception);
    }
  }

  private static boolean matches(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
    if (!privateKey.getModulus().equals(publicKey.getModulus())) return false;
    try {
      byte[] proof = "hooshix:identity:jwt-key-pair-check:v1".getBytes(StandardCharsets.US_ASCII);
      java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
      signer.initSign(privateKey);
      signer.update(proof);
      byte[] signature = signer.sign();
      try {
        java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(proof);
        return verifier.verify(signature);
      } finally {
        java.util.Arrays.fill(signature, (byte) 0);
      }
    } catch (GeneralSecurityException exception) {
      return false;
    }
  }

  private static void requireNoKeyIdRebinding(
      Map<String, ? extends java.security.Key> previous,
      Map<String, ? extends java.security.Key> candidate,
      String keyKind) {
    for (Map.Entry<String, ? extends java.security.Key> entry : previous.entrySet()) {
      java.security.Key replacement = candidate.get(entry.getKey());
      if (replacement != null && !sameKey(entry.getValue(), replacement)) {
        throw new IllegalStateException("JWT " + keyKind + " key identifier cannot be rebound");
      }
    }
  }

  private static boolean sameKey(java.security.Key left, java.security.Key right) {
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

  private static void require3072(BigInteger modulus) {
    if (modulus.bitLength() != RSA_BITS) {
      throw new IllegalStateException("JWT RSA key must be exactly 3072 bits");
    }
  }

  private static String keyId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new IllegalStateException("JWT key identifier is invalid");
    }
    return value;
  }

  private static Properties load(Path path) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      properties.load(reader);
      return properties;
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to load local JWT key ring", exception);
    }
  }

  private static String required(Properties properties, String name) {
    String value = properties.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("JWT key-ring property is missing");
    }
    return value.trim();
  }

  private record PublicBundle(Map<String, RSAPublicKey> keys) {}

  private record Snapshot(
      RsaSigningKeyMaterial active,
      Map<String, RSAPrivateKey> privateKeys,
      Map<String, RSAPublicKey> publicKeys,
      Instant loadedAt) {}
}
