package com.sajtech.conversation.infrastructure.security.keyring;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedContentKeyRingTest {
  @TempDir Path directory;

  @Test
  void loadsRotatesAndRejectsKeyLossOrRebinding() throws Exception {
    Path file = directory.resolve("content.properties");
    write(file, "k1", key(1), null, null);
    var clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
    var ring = new FileBackedContentKeyRing(file, clock, Duration.ofMinutes(2));

    assertThat(ring.activeKey().keyId()).isEqualTo("k1");
    assertThat(ring.key("k1").getEncoded()).hasSize(32);
    assertThat(ring.isFresh()).isTrue();

    write(file, "k2", key(1), key(2), null);
    ring.refresh();
    assertThat(ring.activeKey().keyId()).isEqualTo("k2");
    assertThat(ring.key("k1").getEncoded()).hasSize(32);

    write(file, "k2", key(9), key(2), null);
    assertThatThrownBy(ring::refresh)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be rebound");

    write(file, "k2", null, key(2), null);
    assertThatThrownBy(ring::refresh)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be removed");
  }

  @Test
  void rejectsMissingMalformedAndUnknownKeys() throws Exception {
    Path file = directory.resolve("content.properties");
    Files.writeString(file, "key.k1=%%%\n");
    assertThatThrownBy(
            () -> new FileBackedContentKeyRing(file, Clock.systemUTC(), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalStateException.class);

    write(file, "k1", key(1), null, "key.invalid/id=" + key(3));
    assertThatThrownBy(
            () -> new FileBackedContentKeyRing(file, Clock.systemUTC(), Duration.ofMinutes(1)))
        .isInstanceOf(IllegalStateException.class);

    write(file, "k1", key(1), null, null);
    var ring = new FileBackedContentKeyRing(file, Clock.systemUTC(), Duration.ofMinutes(1));
    assertThatThrownBy(() -> ring.key("missing"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void expiresExactlyAtMaximumStaleness() throws Exception {
    Path file = directory.resolve("content.properties");
    write(file, "k1", key(1), null, null);
    Instant loadedAt = Instant.parse("2026-09-12T12:00:00Z");
    var clock = new MutableClock(loadedAt);
    var ring = new FileBackedContentKeyRing(file, clock, Duration.ofMinutes(2));

    clock.set(loadedAt.plus(Duration.ofMinutes(2)));

    assertThat(ring.isFresh()).isFalse();
    assertThatThrownBy(ring::activeKey)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stale");
  }

  private static void write(Path file, String active, String first, String second, String extra)
      throws Exception {
    StringBuilder value = new StringBuilder("active_key_id=").append(active).append('\n');
    if (first != null) value.append("key.k1=").append(first).append('\n');
    if (second != null) value.append("key.k2=").append(second).append('\n');
    if (extra != null) value.append(extra).append('\n');
    Files.writeString(file, value);
  }

  private static String key(int fill) {
    byte[] value = new byte[32];
    java.util.Arrays.fill(value, (byte) fill);
    return Base64.getEncoder().encodeToString(value);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant replacement) {
      instant = replacement;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
