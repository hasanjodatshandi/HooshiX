package com.sajtech.authorization.infrastructure.security;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class HmacIntentFingerprintTest {
  @TempDir Path temp;
  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void equalIntentMatchesStoredPreviousKeyAfterRotation() throws Exception {
    Path ring = temp.resolve("keys.properties");
    String k1 = Base64.getEncoder().encodeToString(bytes((byte) 1));
    String k2 = Base64.getEncoder().encodeToString(bytes((byte) 2));
    Files.writeString(ring, "active_key_id=k1\nkey.k1=" + k1 + "\nkey.k2=" + k2 + "\n");
    FileBackedKeyRing keys =
        new FileBackedKeyRing(ring, "HmacSHA256", 32, clock, Duration.ofMinutes(5));
    HmacIntentFingerprint fingerprint = new HmacIntentFingerprint(keys);
    var before = fingerprint.fingerprint("CREATE_ROLE", "a", "b");
    byte[] stored = before.activeValue();
    Files.writeString(ring, "active_key_id=k2\nkey.k1=" + k1 + "\nkey.k2=" + k2 + "\n");
    keys.refresh();
    var after = fingerprint.fingerprint("CREATE_ROLE", "a", "b");
    assertThat(after.activeKeyId()).isEqualTo("k2");
    assertThat(after.matches(before.version(), "k1", stored)).isTrue();
    assertThat(after.matches(before.version(), "k1", new byte[32])).isFalse();
  }

  private static byte[] bytes(byte value) {
    byte[] b = new byte[32];
    java.util.Arrays.fill(b, value);
    return b;
  }
}
