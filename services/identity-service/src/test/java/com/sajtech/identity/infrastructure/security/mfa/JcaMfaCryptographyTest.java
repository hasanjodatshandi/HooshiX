package com.sajtech.identity.infrastructure.security.mfa;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JcaMfaCryptographyTest {
  private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
  @TempDir Path temp;

  @Test
  void generatedSecretUsesSha256TotpAndIsBoundToUserAndEnrollment() throws Exception {
    JcaMfaCryptography crypto = cryptography();
    UUID userId = UUID.randomUUID();
    UUID enrollmentId = UUID.randomUUID();

    var generated = crypto.generateTotpSecret(userId, enrollmentId);
    String code = totp(decodeBase32(generated.base32()), NOW.getEpochSecond() / 30);

    assertThat(generated.base32()).matches("[A-Z2-7]{52}");
    assertThat(generated.otpauthUri())
        .startsWith("otpauth://totp/")
        .contains("issuer=SajTech", "algorithm=SHA256", "digits=6", "period=30");
    assertThat(crypto.verifyTotp(userId, enrollmentId, generated.encrypted(), code, NOW))
        .hasValue(NOW.getEpochSecond() / 30);
    assertThatThrownByBinding(crypto, userId, enrollmentId, generated.encrypted(), code);
  }

  @Test
  void totpAcceptsOnlyTheThreeStepWindow() throws Exception {
    JcaMfaCryptography crypto = cryptography();
    UUID userId = UUID.randomUUID();
    UUID enrollmentId = UUID.randomUUID();
    var generated = crypto.generateTotpSecret(userId, enrollmentId);
    byte[] secret = decodeBase32(generated.base32());
    long step = NOW.getEpochSecond() / 30;

    assertThat(
            crypto.verifyTotp(
                userId, enrollmentId, generated.encrypted(), totp(secret, step - 1), NOW))
        .hasValue(step - 1);
    assertThat(
            crypto.verifyTotp(userId, enrollmentId, generated.encrypted(), totp(secret, step), NOW))
        .hasValue(step);
    assertThat(
            crypto.verifyTotp(
                userId, enrollmentId, generated.encrypted(), totp(secret, step + 1), NOW))
        .hasValue(step + 1);
    assertThat(
            crypto.verifyTotp(
                userId, enrollmentId, generated.encrypted(), totp(secret, step + 2), NOW))
        .isEmpty();
  }

  @Test
  void challengesAndRecoveryCodesAreOpaqueUniqueAndPurposeBound() throws Exception {
    JcaMfaCryptography crypto = cryptography();
    UUID enrollmentId = UUID.randomUUID();
    var challenge = crypto.generateChallenge();
    var codes = crypto.generateRecoveryCodes(enrollmentId);

    assertThat(challenge.encoded()).matches("[A-Za-z0-9_-]{43}");
    assertThat(crypto.challengeDigestCandidates(challenge.encoded()))
        .anySatisfy(
            candidate -> assertThat(candidate.digest()).isEqualTo(challenge.digest().digest()));
    assertThat(codes).hasSize(10);
    assertThat(codes.stream().map(code -> code.encoded()).toList())
        .allMatch(code -> code.matches("[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}"));
    assertThat(new HashSet<>(codes.stream().map(code -> code.encoded()).toList())).hasSize(10);
    assertThat(crypto.recoveryDigestCandidates(enrollmentId, codes.getFirst().encoded()))
        .anySatisfy(
            candidate ->
                assertThat(candidate.digest()).isEqualTo(codes.getFirst().digest().digest()));
    assertThat(crypto.recoveryDigestCandidates(UUID.randomUUID(), codes.getFirst().encoded()))
        .noneMatch(
            candidate -> Arrays.equals(candidate.digest(), codes.getFirst().digest().digest()));
    assertThat(crypto.challengeDigestCandidates(codes.getFirst().encoded())).isEmpty();
  }

  private static void assertThatThrownByBinding(
      JcaMfaCryptography crypto,
      UUID userId,
      UUID enrollmentId,
      com.sajtech.identity.application.mfa.model.EncryptedTotpSecret encrypted,
      String code) {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> crypto.verifyTotp(UUID.randomUUID(), enrollmentId, encrypted, code, NOW))
        .isInstanceOf(IllegalStateException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> crypto.verifyTotp(userId, UUID.randomUUID(), encrypted, code, NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  private JcaMfaCryptography cryptography() throws Exception {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    return new JcaMfaCryptography(
        ring("encryption", "AES", (byte) 3, clock), ring("digest", "HmacSHA256", (byte) 7, clock));
  }

  private FileBackedKeyRing ring(String name, String algorithm, byte fill, Clock clock)
      throws Exception {
    byte[] key = new byte[32];
    Arrays.fill(key, fill);
    Path path = temp.resolve(name + ".properties");
    Files.writeString(
        path, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");
    return new FileBackedKeyRing(path, algorithm, 32, clock, Duration.ofHours(1));
  }

  private static String totp(byte[] secret, long timestep) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timestep).array());
    int offset = digest[digest.length - 1] & 0x0f;
    int binary =
        ((digest[offset] & 0x7f) << 24)
            | ((digest[offset + 1] & 0xff) << 16)
            | ((digest[offset + 2] & 0xff) << 8)
            | (digest[offset + 3] & 0xff);
    return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
  }

  private static byte[] decodeBase32(String encoded) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    byte[] result = new byte[encoded.length() * 5 / 8];
    int buffer = 0;
    int bits = 0;
    int index = 0;
    for (char value : encoded.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(value);
      bits += 5;
      if (bits >= 8) {
        result[index++] = (byte) (buffer >> (bits - 8));
        bits -= 8;
      }
    }
    return result;
  }
}
