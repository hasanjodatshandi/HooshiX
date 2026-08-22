package com.sajtech.identity.infrastructure.security.jwt;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.authentication.model.AccessTokenContext;
import com.sajtech.identity.application.authentication.model.SignedAccessToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaJwtAccessTokenSignerTest {
  @TempDir Path temp;

  @Test
  void signsRs256WithExactFiveMinuteMinimalClaimContractAndCurrentPublishedKey() throws Exception {
    KeyPair current = keyPair(3072);
    KeyPair next = keyPair(3072);
    KeyPair previous = keyPair(3072);
    Path privatePath = temp.resolve("signing.properties");
    Path publicPath = temp.resolve("verifier.properties");
    Files.writeString(
        privatePath,
        "active_key_id=current-kid\nkey.current-kid="
            + Base64.getEncoder().encodeToString(current.getPrivate().getEncoded())
            + "\n");
    Files.writeString(
        publicPath,
        "current_key_id=current-kid\n"
            + "next_key_id=next-kid\n"
            + "previous_key_id=previous-kid\n"
            + "key.current-kid="
            + Base64.getEncoder().encodeToString(current.getPublic().getEncoded())
            + "\nkey.next-kid="
            + Base64.getEncoder().encodeToString(next.getPublic().getEncoded())
            + "\nkey.previous-kid="
            + Base64.getEncoder().encodeToString(previous.getPublic().getEncoded())
            + "\n");
    FileBackedRsaSigningKeyRing ring =
        new FileBackedRsaSigningKeyRing(
            privatePath, publicPath, Clock.systemUTC(), Duration.ofMinutes(5));
    RsaJwtAccessTokenSigner signer =
        new RsaJwtAccessTokenSigner("https://identity.sajtech.internal", ring);
    Instant issued = Instant.parse("2026-08-18T12:00:00.999999Z");

    SignedAccessToken result =
        signer.sign(
            new AccessTokenContext(
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                "s".repeat(43),
                "authorization-service",
                issued));

    String[] parts = result.token().split("\\.");
    assertThat(parts).hasSize(3);
    String header = decode(parts[0]);
    String claims = decode(parts[1]);
    assertThat(header).contains("\"alg\":\"RS256\"").contains("\"kid\":\"current-kid\"");
    assertThat(claims)
        .contains("\"iss\":\"https://identity.sajtech.internal\"")
        .contains("\"aud\":\"authorization-service\"")
        .contains("\"sub\":\"11111111-1111-4111-8111-111111111111\"")
        .contains("\"tenant_id\":\"22222222-2222-4222-8222-222222222222\"")
        .contains("\"membership_id\":\"33333333-3333-4333-8333-333333333333\"")
        .contains("\"sid\":\"" + "s".repeat(43) + "\"")
        .contains("\"iat\":1787054400")
        .contains("\"exp\":1787054700")
        .doesNotContain("roles")
        .doesNotContain("permissions");
    assertThat(result.expiresAt()).isEqualTo(Instant.ofEpochSecond(issued.getEpochSecond() + 300));

    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(current.getPublic());
    verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
    assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
  }

  @Test
  void rejectsDuplicateCurrentNextKidRolesBeforeSnapshotActivation() throws Exception {
    KeyPair current = keyPair(3072);
    Path privatePath = temp.resolve("duplicate-private.properties");
    Path publicPath = temp.resolve("duplicate-public.properties");
    Files.writeString(
        privatePath,
        "active_key_id=current-kid\nkey.current-kid="
            + Base64.getEncoder().encodeToString(current.getPrivate().getEncoded())
            + "\n");
    Files.writeString(
        publicPath,
        "current_key_id=current-kid\nnext_key_id=current-kid\nkey.current-kid="
            + Base64.getEncoder().encodeToString(current.getPublic().getEncoded())
            + "\n");

    assertThatThrownBy(
            () ->
                new FileBackedRsaSigningKeyRing(
                    privatePath, publicPath, Clock.systemUTC(), Duration.ofMinutes(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("distinct");
  }

  @Test
  void rejectsNon3072PrivateKeyBeforeSnapshotActivation() throws Exception {
    KeyPair weak = keyPair(2048);
    Path privatePath = temp.resolve("weak-private.properties");
    Path publicPath = temp.resolve("weak-public.properties");
    Files.writeString(
        privatePath,
        "active_key_id=weak\nkey.weak="
            + Base64.getEncoder().encodeToString(weak.getPrivate().getEncoded())
            + "\n");
    Files.writeString(
        publicPath,
        "current_key_id=weak\nkey.weak="
            + Base64.getEncoder().encodeToString(weak.getPublic().getEncoded())
            + "\n");

    assertThatThrownBy(
            () ->
                new FileBackedRsaSigningKeyRing(
                    privatePath, publicPath, Clock.systemUTC(), Duration.ofMinutes(5)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3072");
  }

  @Test
  void rejectsKidRebindingAndKeepsPreviousSignerSnapshot() throws Exception {
    KeyPair original = keyPair(3072);
    KeyPair replacement = keyPair(3072);
    Path privatePath = temp.resolve("rebind-private.properties");
    Path publicPath = temp.resolve("rebind-public.properties");
    writePair(privatePath, publicPath, "stable-kid", original);
    FileBackedRsaSigningKeyRing ring =
        new FileBackedRsaSigningKeyRing(
            privatePath, publicPath, Clock.systemUTC(), Duration.ofMinutes(5));

    writePair(privatePath, publicPath, "stable-kid", replacement);

    assertThatThrownBy(ring::refresh)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be rebound");
    assertThat(ring.activeKey().keyId()).isEqualTo("stable-kid");
    assertThat(ring.activeKey().publicKey().getModulus())
        .isEqualTo(((java.security.interfaces.RSAPublicKey) original.getPublic()).getModulus());
  }

  private static void writePair(Path privatePath, Path publicPath, String keyId, KeyPair pair)
      throws Exception {
    Files.writeString(
        privatePath,
        String.join(
            System.lineSeparator(),
            "active_key_id=" + keyId,
            "key."
                + keyId
                + "="
                + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
            ""));
    Files.writeString(
        publicPath,
        String.join(
            System.lineSeparator(),
            "current_key_id=" + keyId,
            "key."
                + keyId
                + "="
                + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            ""));
  }

  private static KeyPair keyPair(int bits) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(bits);
    return generator.generateKeyPair();
  }

  private static String decode(String encoded) {
    return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
  }
}
