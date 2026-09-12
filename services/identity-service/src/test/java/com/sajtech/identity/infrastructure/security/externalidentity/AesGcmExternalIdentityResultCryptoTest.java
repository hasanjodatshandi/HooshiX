package com.sajtech.identity.infrastructure.security.externalidentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmExternalIdentityResultCryptoTest {
  @TempDir Path temp;

  @Test
  void roundTripBindsCiphertextToEvidenceAndOperation() throws Exception {
    AesGcmExternalIdentityResultCrypto crypto = new AesGcmExternalIdentityResultCrypto(keyRing());
    byte[] evidenceId = "evidence-1".getBytes(StandardCharsets.US_ASCII);
    byte[] clear = "issuer-subject-result".getBytes(StandardCharsets.UTF_8);

    var encrypted = crypto.encrypt(evidenceId, "google-callback", clear);

    assertThat(encrypted.keyId()).isEqualTo("v1");
    assertThat(encrypted.nonce()).hasSize(12);
    assertThat(encrypted.nonce()).isNotEqualTo(new byte[12]);
    assertThat(encrypted.ciphertext()).isNotEqualTo(clear);
    assertThat(crypto.decrypt(evidenceId, "google-callback", encrypted)).containsExactly(clear);
    assertThatThrownBy(() -> crypto.decrypt(evidenceId, "different-operation", encrypted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("OIDC evidence result protection is unavailable");
  }

  private FileBackedKeyRing keyRing() throws Exception {
    Path path = temp.resolve("oidc-result.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 23);
    Files.writeString(
        path, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    return new FileBackedKeyRing(path, "AES", 32, Clock.systemUTC(), Duration.ofHours(1));
  }
}
