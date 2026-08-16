package com.sajtech.notification.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.infrastructure.security.fingerprint.FileBackedHmacIntentFingerprint;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedHmacIntentFingerprintTest {
  @TempDir Path tempDirectory;

  @Test
  void retainedHistoricalKeyVerifiesReplayAfterActiveKeyRotation() throws Exception {
    Path path = tempDirectory.resolve("fingerprint.properties");
    byte[] v1 = new byte[32];
    byte[] v2 = new byte[32];
    Arrays.fill(v1, (byte) 7);
    Arrays.fill(v2, (byte) 11);
    Files.writeString(path, properties("v1", v1, null), StandardCharsets.UTF_8);
    FileBackedKeyRing ring =
        new FileBackedKeyRing(
            path, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofMinutes(2));
    FileBackedHmacIntentFingerprint fingerprints = new FileBackedHmacIntentFingerprint(ring);
    byte[] canonicalIntent = "canonical-intent".getBytes(StandardCharsets.UTF_8);
    var stored = fingerprints.compute(canonicalIntent);

    Files.writeString(path, properties("v2", v1, v2), StandardCharsets.UTF_8);
    ring.refresh();
    var current = fingerprints.compute(canonicalIntent);

    assertThat(current.keyId()).isEqualTo("v2");
    assertThat(
            fingerprints.verify(
                canonicalIntent, stored.version(), stored.keyId(), stored.value()))
        .isTrue();
    assertThat(
            fingerprints.verify(
                "different-intent".getBytes(StandardCharsets.UTF_8),
                stored.version(),
                stored.keyId(),
                stored.value()))
        .isFalse();
  }

  private static String properties(String activeKeyId, byte[] v1, byte[] v2) {
    StringBuilder result =
        new StringBuilder()
            .append("active_key_id=")
            .append(activeKeyId)
            .append('\n')
            .append("key.v1=")
            .append(Base64.getEncoder().encodeToString(v1))
            .append('\n');
    if (v2 != null) {
      result.append("key.v2=").append(Base64.getEncoder().encodeToString(v2)).append('\n');
    }
    return result.toString();
  }
}
