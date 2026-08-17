package com.sajtech.notification.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedKeyRingTest {
  @TempDir Path tempDirectory;

  @Test
  void loadsOnlyExactLengthKeysAndFailsWhenSnapshotBecomesStale() throws Exception {
    Path path = tempDirectory.resolve("keys.properties");
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(path, "active_key_id=v1\nkey.v1=" + key + "\n", StandardCharsets.UTF_8);
    MutableClock clock = new MutableClock(Instant.parse("2026-08-16T00:00:00Z"));
    FileBackedKeyRing ring = new FileBackedKeyRing(path, "AES", 32, clock, Duration.ofHours(1));

    assertThat(ring.activeKey().keyId()).isEqualTo("v1");
    clock.advance(Duration.ofHours(1).plusMillis(1));
    assertThatThrownBy(ring::activeKey)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale");
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
