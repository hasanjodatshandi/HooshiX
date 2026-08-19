package com.sajtech.identity.infrastructure.security.session;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.authentication.model.GeneratedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacSessionCredentialTest {
  @TempDir Path temp;

  @Test
  void generatesExact256BitBase64UrlSecretsAndVerifiesAcrossRetainedKeyRotation() throws Exception {
    Path ringPath = temp.resolve("refresh.properties");
    byte[] k1 = new byte[32];
    byte[] k2 = new byte[32];
    java.util.Arrays.fill(k1, (byte) 1);
    java.util.Arrays.fill(k2, (byte) 2);
    writeRing(ringPath, "k1", k1, null, null);
    FileBackedKeyRing ring =
        new FileBackedKeyRing(ringPath, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofMinutes(5));
    HmacSessionCredential credentials = new HmacSessionCredential(ring);

    GeneratedRefreshCredential first = credentials.newRefreshCredential();
    String sessionId = credentials.newSessionId();

    assertThat(first.encoded()).hasSize(43).doesNotContain("=");
    assertThat(Base64.getUrlDecoder().decode(first.encoded())).hasSize(32);
    assertThat(sessionId).hasSize(43).doesNotContain("=");
    assertThat(Base64.getUrlDecoder().decode(sessionId)).hasSize(32);
    assertThat(first.digest().keyId()).isEqualTo("k1");
    assertThat(first.digest().version()).isEqualTo("refresh-hmac-v1");
    assertThat(first.digest().digest()).hasSize(32);

    writeRing(ringPath, "k2", k1, "k2", k2);
    ring.refresh();
    List<RefreshDigest> candidates = credentials.digestCandidates(first.encoded());

    assertThat(candidates).extracting(RefreshDigest::keyId).containsExactly("k1", "k2");
    assertThat(candidates.stream().filter(candidate -> candidate.keyId().equals("k1")).findFirst())
        .get()
        .extracting(RefreshDigest::digest)
        .isEqualTo(first.digest().digest());
    assertThat(credentials.newRefreshCredential().digest().keyId()).isEqualTo("k2");
    assertThatThrownBy(() -> credentials.digestCandidates(first.encoded() + "="))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void writeRing(
      Path path, String active, byte[] first, String secondId, byte[] second) throws Exception {
    StringBuilder value =
        new StringBuilder()
            .append("active_key_id=")
            .append(active)
            .append('\n')
            .append("key.k1=")
            .append(Base64.getEncoder().encodeToString(first))
            .append('\n');
    if (secondId != null) {
      value
          .append("key.")
          .append(secondId)
          .append('=')
          .append(Base64.getEncoder().encodeToString(second))
          .append('\n');
    }
    Files.writeString(path, value.toString());
  }
}
