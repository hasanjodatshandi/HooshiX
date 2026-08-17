package com.sajtech.notification.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.infrastructure.security.fingerprint.FileBackedHmacIntentFingerprint;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedHmacIntentFingerprintTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void computesAndVerifiesVersionedHmacFingerprint() throws Exception {
    writeKeyRing("fingerprint-key-1");
    var fingerprint =
        new FileBackedHmacIntentFingerprint(new FileBackedKeyRing(directory, Duration.ofMinutes(5)));

    var digest = fingerprint.compute("intent".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(digest.version()).isEqualTo("hmac-sha256-v1");
    assertThat(digest.keyId()).isEqualTo("fingerprint-key-1");
    assertThat(
            fingerprint.verify(
                "intent".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                digest.version(),
                digest.keyId(),
                digest.value()))
        .isTrue();
  }

  @Test
  void verifiesRetainedHistoricalKeyAfterActiveKeyRotation() throws Exception {
    writeKeyRing("fingerprint-key-1");
    var keyRing = new FileBackedKeyRing(directory, Duration.ofMinutes(5));
    var fingerprint = new FileBackedHmacIntentFingerprint(keyRing);
    byte[] material = "intent".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var original = fingerprint.compute(material);

    writeKeyRing("fingerprint-key-2");
    keyRing.reload();

    assertThat(fingerprint.compute(material).keyId()).isEqualTo("fingerprint-key-2");
    assertThat(
            fingerprint.verify(
                material,
                original.version(),
                original.keyId(),
                original.value()))
        .isTrue();
  }

  @Test
  void failsWhenHistoricalKeyWasRemoved() throws Exception {
    writeKeyRing("fingerprint-key-1");
    var keyRing = new FileBackedKeyRing(directory, Duration.ofMinutes(5));
    var fingerprint = new FileBackedHmacIntentFingerprint(keyRing);
    byte[] material = "intent".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var original = fingerprint.compute(material);

    String key = Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(
        directory.resolve("key-ring.properties"),
        """
        fingerprint.active-key-id=fingerprint-key-2
        fingerprint.key.fingerprint-key-2=%s
        delivery.active-key-id=delivery-key-1
        delivery.key.delivery-key-1=%s
        """
            .formatted(key, key));
    keyRing.reload();

    assertThatThrownBy(
            () ->
                fingerprint.verify(
                    material,
                    original.version(),
                    original.keyId(),
                    original.value()))
        .isInstanceOf(IllegalStateException.class);
  }

  private void writeKeyRing(String activeFingerprintKeyId) throws Exception {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(
        directory.resolve("key-ring.properties"),
        """
        fingerprint.active-key-id=%s
        fingerprint.key.fingerprint-key-1=%s
        fingerprint.key.fingerprint-key-2=%s
        delivery.active-key-id=delivery-key-1
        delivery.key.delivery-key-1=%s
        """
            .formatted(activeFingerprintKeyId, key, key, key));
  }
}
