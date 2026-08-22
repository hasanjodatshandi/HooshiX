package com.sajtech.webbff.infrastructure.security.keyring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedKeyRingTest {
  @TempDir Path temp;

  @Test
  void rejectsKeyIdRebindingAndPreservesLastValidSnapshot() throws Exception {
    Path path = temp.resolve("keys.properties");
    byte[] original = new byte[32];
    byte[] replacement = new byte[32];
    Arrays.fill(original, (byte) 7);
    Arrays.fill(replacement, (byte) 11);
    write(path, "stable-key", original);
    FileBackedKeyRing ring =
        new FileBackedKeyRing(path, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofHours(1));

    write(path, "stable-key", replacement);

    assertThatThrownBy(ring::refresh)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be rebound");
    assertThat(ring.activeKey().keyId()).isEqualTo("stable-key");
    assertThat(ring.activeKey().key().getEncoded()).containsExactly(original);
  }

  private static void write(Path path, String keyId, byte[] key) throws Exception {
    Files.writeString(
        path,
        String.join(
            System.lineSeparator(),
            "active_key_id=" + keyId,
            "key." + keyId + "=" + Base64.getEncoder().encodeToString(key),
            ""));
  }
}
