package com.sajtech.notification.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedKeyRingTest {
  @TempDir java.nio.file.Path directory;

  @Test
  void loadsActiveKeysAndFailsClosedWhenKeyIsMissing() throws Exception {
    String key = Base64.getEncoder().encodeToString(new byte[32]);
    Files.writeString(
        directory.resolve("key-ring.properties"),
        """
        fingerprint.active-key-id=fingerprint-key-1
        fingerprint.key.fingerprint-key-1=%s
        delivery.active-key-id=delivery-key-1
        delivery.key.delivery-key-1=%s
        """
            .formatted(key, key));

    var keyRing = new FileBackedKeyRing(directory, Duration.ofMinutes(5));

    assertThat(keyRing.activeFingerprintKey().keyId()).isEqualTo("fingerprint-key-1");
    assertThat(keyRing.activeDeliveryKey().keyId()).isEqualTo("delivery-key-1");
    assertThatThrownBy(() -> keyRing.fingerprintKey("missing"))
        .isInstanceOf(IllegalStateException.class);
  }
}
