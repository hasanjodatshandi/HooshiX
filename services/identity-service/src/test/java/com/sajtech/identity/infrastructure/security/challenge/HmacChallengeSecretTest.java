package com.sajtech.identity.infrastructure.security.challenge;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacChallengeSecretTest {
  @TempDir Path temp;

  @Test
  void generatedCodeIsEightDigitsAndOnlyVerifierIsRequiredForProof() throws Exception {
    Path ring = temp.resolve("challenge.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 3);
    Files.writeString(
        ring, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    FileBackedKeyRing keys =
        new FileBackedKeyRing(ring, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofHours(1));
    HmacChallengeSecret secrets = new HmacChallengeSecret(keys);
    UUID id = UUID.randomUUID();

    var generated = secrets.generate(id);

    assertThat(generated.code()).matches("[0-9]{8}");
    assertThat(generated.verifier()).hasSize(32);
    assertThat(secrets.matches(id, generated.code(), generated.verifier(), generated.keyId()))
        .isTrue();
    assertThat(secrets.matches(id, "00000000", generated.verifier(), generated.keyId()))
        .isEqualTo("00000000".equals(generated.code()));
  }

  @Test
  void contactAndRegistrationProofNamespacesCannotVerifyEachOther() throws Exception {
    Path ring = temp.resolve("purpose.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    Files.writeString(
        ring, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    FileBackedKeyRing keys =
        new FileBackedKeyRing(ring, "HmacSHA256", 32, Clock.systemUTC(), Duration.ofHours(1));
    HmacChallengeSecret registration = new HmacChallengeSecret(keys);
    HmacContactVerificationSecret contact = new HmacContactVerificationSecret(keys);
    UUID challengeId = UUID.randomUUID();
    var registrationProof = registration.generate(challengeId);

    assertThat(
            contact.matches(
                challengeId,
                registrationProof.code(),
                registrationProof.verifier(),
                registrationProof.keyId()))
        .isFalse();
  }
}
