package com.sajtech.authorization.infrastructure.security;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.application.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class IdentityJwtVerifierTest {
  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  @TempDir Path temp;
  private KeyPair pair;
  private IdentityJwtVerifier verifier;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    pair = generator.generateKeyPair();
    Path bundle = temp.resolve("identity-jwt.properties");
    Files.writeString(
        bundle,
        "current_key_id=k1\nkey.k1="
            + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
            + "\n");
    verifier =
        new IdentityJwtVerifier(
            bundle,
            "https://identity.sajtech.internal",
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5));
  }

  @Test
  void validExactAudienceTenantTokenProducesActorContext() throws Exception {
    UUID user = UUID.randomUUID(), tenant = UUID.randomUUID(), membership = UUID.randomUUID();
    String token = token(user, tenant, membership, "authorization-service", "");
    var actor = verifier.verify(token);
    assertThat(actor.userId()).isEqualTo(user);
    assertThat(actor.tenantId()).isEqualTo(tenant);
    assertThat(actor.membershipId()).isEqualTo(membership);
    assertThat(actor.sessionId()).hasSize(43);
  }

  @Test
  void permissionSnapshotClaimIsRejected() throws Exception {
    String token =
        token(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "authorization-service",
            ",\"permissions\":[\"tenant.read\"]");
    assertThatThrownBy(() -> verifier.verify(token))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.INVALID_ACCESS_TOKEN));
  }

  @Test
  void wrongAudienceIsRejected() throws Exception {
    String token = token(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "web-bff", "");
    assertThatThrownBy(() -> verifier.verify(token))
        .isInstanceOfSatisfying(
            AuthorizationException.class,
            e -> assertThat(e.error()).isEqualTo(AuthorizationError.INVALID_ACCESS_TOKEN));
  }

  private String token(UUID user, UUID tenant, UUID membership, String audience, String extra)
      throws Exception {
    String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"k1\"}";
    String claims =
        "{\"iss\":\"https://identity.sajtech.internal\",\"aud\":\""
            + audience
            + "\",\"sub\":\""
            + user
            + "\",\"tenant_id\":\""
            + tenant
            + "\",\"membership_id\":\""
            + membership
            + "\",\"sid\":\""
            + "s".repeat(43)
            + "\",\"jti\":\""
            + UUID.randomUUID()
            + "\",\"iat\":"
            + NOW.getEpochSecond()
            + ",\"exp\":"
            + NOW.plusSeconds(300).getEpochSecond()
            + extra
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
