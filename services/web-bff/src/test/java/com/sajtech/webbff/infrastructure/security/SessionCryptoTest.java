package com.sajtech.webbff.infrastructure.security;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.*;
import java.time.*;
import java.util.Base64;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class SessionCryptoTest {
  @TempDir Path temp;
  private SessionCrypto crypto;
  private Path locator, csrf, refresh;

  @BeforeEach
  void setUp() throws Exception {
    locator = ring("locator.properties", "k1", (byte) 1);
    csrf = ring("csrf.properties", "k1", (byte) 2);
    refresh = ring("refresh.properties", "k1", (byte) 3);
    Clock clock = Clock.fixed(Instant.parse("2026-08-19T12:00:00Z"), ZoneOffset.UTC);
    crypto =
        new SessionCrypto(
            new FileBackedKeyRing(locator, "HmacSHA256", 32, clock, Duration.ofMinutes(5)),
            new FileBackedKeyRing(csrf, "HmacSHA256", 32, clock, Duration.ofMinutes(5)),
            new FileBackedKeyRing(refresh, "AES", 32, clock, Duration.ofHours(1)));
  }

  @Test
  void opaqueSessionCookieHasAtLeast256BitsAndRedisLocatorDoesNotContainRawToken() {
    var issued = crypto.issueSessionToken();
    String[] parts = issued.cookieValue().split("\\.", 2);
    assertThat(parts[0]).isEqualTo("k1");
    assertThat(parts[1]).hasSize(43);
    assertThat(issued.locator()).doesNotContain(parts[1]);
    assertThat(crypto.locatorFromCookie(issued.cookieValue())).isEqualTo(issued.locator());
  }

  @Test
  void csrfStoresOnlyDigestAndUsesConstantTimeMatchContract() {
    var csrf = crypto.issueCsrf();
    assertThat(csrf.clear()).hasSize(43);
    assertThat(csrf.digestHex()).hasSize(64).doesNotContain(csrf.clear());
    assertThat(crypto.csrfMatches(csrf.clear(), csrf.keyId(), csrf.digestHex())).isTrue();
    assertThat(crypto.csrfMatches("x".repeat(43), csrf.keyId(), csrf.digestHex())).isFalse();
  }

  @Test
  void retainedRefreshUsesSessionBoundAesGcm() {
    var one = crypto.issueSessionToken();
    var two = crypto.issueSessionToken();
    var encrypted = crypto.encryptRefresh(one.locator(), "refresh-secret");
    assertThat(encrypted.nonce()).isNotBlank();
    assertThat(encrypted.ciphertext()).doesNotContain("refresh-secret");
    assertThat(crypto.decryptRefresh(one.locator(), encrypted)).isEqualTo("refresh-secret");
    assertThatThrownBy(() -> crypto.decryptRefresh(two.locator(), encrypted))
        .isInstanceOf(IllegalStateException.class);
  }

  private Path ring(String name, String active, byte fill) throws Exception {
    Path p = temp.resolve(name);
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, fill);
    Files.writeString(
        p,
        "active_key_id="
            + active
            + "\nkey."
            + active
            + "="
            + Base64.getEncoder().encodeToString(key)
            + "\n");
    return p;
  }
}
