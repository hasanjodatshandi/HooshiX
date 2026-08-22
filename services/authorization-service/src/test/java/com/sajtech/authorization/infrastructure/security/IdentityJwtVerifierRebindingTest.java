package com.sajtech.authorization.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.authorization.application.AuthorizationError;
import com.sajtech.authorization.application.AuthorizationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityJwtVerifierRebindingTest {
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  @TempDir Path temp;

  @Test
  void rejectsKidRebindingAndPreservesLastValidSnapshot() throws Exception {
    KeyPair original = keyPair();
    KeyPair replacement = keyPair();
    Path bundle = temp.resolve("identity-jwt.properties");
    write(bundle, original);
    IdentityJwtVerifier verifier =
        new IdentityJwtVerifier(
            bundle,
            "https://identity.sajtech.internal",
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5));
    String originalToken = token(original);

    write(bundle, replacement);

    assertThatThrownBy(verifier::refresh)
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            error ->
                assertThat(error.error()).isEqualTo(AuthorizationError.AUTHORIZATION_UNAVAILABLE));
    assertThatCode(() -> verifier.verify(originalToken)).doesNotThrowAnyException();
  }

  private static KeyPair keyPair() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    return generator.generateKeyPair();
  }

  private static void write(Path bundle, KeyPair pair) throws Exception {
    Files.writeString(
        bundle,
        String.join(
            System.lineSeparator(),
            "current_key_id=stable-kid",
            "key.stable-kid=" + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
            ""));
  }

  private static String token(KeyPair pair) throws Exception {
    String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"stable-kid\"}";
    String claims =
        "{\"iss\":\"https://identity.sajtech.internal\",\"aud\":\"authorization-service\",\"sub\":\""
            + UUID.randomUUID()
            + "\",\"tenant_id\":\""
            + UUID.randomUUID()
            + "\",\"membership_id\":\""
            + UUID.randomUUID()
            + "\",\"sid\":\""
            + "s".repeat(43)
            + "\",\"jti\":\""
            + UUID.randomUUID()
            + "\",\"iat\":"
            + NOW.getEpochSecond()
            + ",\"exp\":"
            + NOW.plusSeconds(300).getEpochSecond()
            + "}";
    String encoded =
        B64.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + B64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(pair.getPrivate());
    signature.update(encoded.getBytes(StandardCharsets.US_ASCII));
    return encoded + "." + B64.encodeToString(signature.sign());
  }
}
