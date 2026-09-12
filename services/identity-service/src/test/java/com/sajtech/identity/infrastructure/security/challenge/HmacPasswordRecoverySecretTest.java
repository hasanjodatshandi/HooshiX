package com.sajtech.identity.infrastructure.security.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacPasswordRecoverySecretTest {
  @TempDir Path temp;

  @Test
  void generatedProofMatchesOnlyItsChallengeCodeAndVerifierShape() throws Exception {
    HmacPasswordRecoverySecret secrets = new HmacPasswordRecoverySecret(keyRing());
    UUID challengeId = UUID.randomUUID();

    var generated = secrets.generate(challengeId);

    assertThat(generated.code()).matches("[0-9]{8}");
    assertThat(generated.verifier()).hasSize(32);
    assertThat(
            secrets.matches(challengeId, generated.code(), generated.verifier(), generated.keyId()))
        .isTrue();
    assertThat(
            secrets.matches(
                UUID.randomUUID(), generated.code(), generated.verifier(), generated.keyId()))
        .isFalse();
    assertThat(secrets.matches(challengeId, "1234", generated.verifier(), generated.keyId()))
        .isFalse();
    assertThat(secrets.matches(challengeId, "1234567a", generated.verifier(), generated.keyId()))
        .isFalse();
    assertThat(
            secrets.matches(
                challengeId,
                "1234567a",
                expectedVerifier(challengeId, "1234567a"),
                generated.keyId()))
        .isFalse();
    assertThat(secrets.matches(challengeId, null, generated.verifier(), generated.keyId()))
        .isFalse();
    assertThat(secrets.matches(challengeId, generated.code(), null, generated.keyId())).isFalse();
    assertThat(secrets.matches(challengeId, generated.code(), new byte[31], generated.keyId()))
        .isFalse();
    assertThat(secrets.matches(challengeId, "12345678", null, "missing-key")).isFalse();
    assertThat(secrets.matches(challengeId, "12345678", new byte[31], "missing-key")).isFalse();
    assertThat(generated.verifier())
        .containsExactly(expectedVerifier(challengeId, generated.code()));
  }

  @Test
  void invalidCodeShapeIsRejectedEvenWhenItsHmacMatches() throws Exception {
    HmacPasswordRecoverySecret secrets = new HmacPasswordRecoverySecret(keyRing());
    UUID challengeId = UUID.randomUUID();
    String invalidCode = "1234567a";

    assertThat(
            secrets.matches(
                challengeId, invalidCode, expectedVerifier(challengeId, invalidCode), "v1"))
        .isFalse();
  }

  private FileBackedKeyRing keyRing() throws Exception {
    Path path = temp.resolve("password-recovery.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 17);
    Files.writeString(
        path, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    return new FileBackedKeyRing(path, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofHours(1));
  }

  private static byte[] expectedVerifier(UUID challengeId, String code)
      throws GeneralSecurityException {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 17);
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    mac.update("hooshix:identity:password-recovery:v1\0".getBytes(StandardCharsets.US_ASCII));
    mac.update(challengeId.toString().getBytes(StandardCharsets.US_ASCII));
    mac.update((byte) 0);
    return mac.doFinal(code.getBytes(StandardCharsets.US_ASCII));
  }
}
