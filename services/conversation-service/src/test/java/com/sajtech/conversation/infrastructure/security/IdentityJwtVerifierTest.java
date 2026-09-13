package com.sajtech.conversation.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityJwtVerifierTest {
  private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");
  private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
  @TempDir Path directory;
  private KeyPair keys;
  private Path bundle;

  @BeforeEach
  void setUp() throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    keys = generator.generateKeyPair();
    bundle = directory.resolve("verifier.properties");
    Files.writeString(
        bundle,
        "current_key_id=k1\nkey.k1="
            + Base64.getEncoder().encodeToString(keys.getPublic().getEncoded())
            + "\n");
  }

  @Test
  void exactConversationAudienceProducesTenantActor() throws Exception {
    UUID user = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID membership = UUID.randomUUID();
    IdentityJwtVerifier verifier = verifier(Clock.fixed(NOW, ZoneOffset.UTC));

    var actor = verifier.verify(token(user, tenant, membership, "conversation-service", "", NOW));

    assertThat(actor.userId()).isEqualTo(user);
    assertThat(actor.tenantId()).isEqualTo(tenant);
    assertThat(actor.membershipId()).isEqualTo(membership);
    assertThat(actor.sessionId()).hasSize(43);
  }

  @Test
  void wrongAudienceIssuerSignatureAndAuthorizationClaimsAreRejected() throws Exception {
    UUID user = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID membership = UUID.randomUUID();
    IdentityJwtVerifier verifier = verifier(Clock.fixed(NOW, ZoneOffset.UTC));

    assertInvalid(verifier, token(user, tenant, membership, "authorization-service", "", NOW));
    assertInvalid(
        verifier,
        token(
            user,
            tenant,
            membership,
            "conversation-service",
            ",\"permissions\":[\"conversation.read\"]",
            NOW));
    assertInvalid(
        verifier, token(user, tenant, membership, "conversation-service", "", NOW).concat("x"));
    IdentityJwtVerifier wrongIssuer =
        new IdentityJwtVerifier(
            bundle,
            "https://other.invalid",
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5));
    assertInvalid(wrongIssuer, token(user, tenant, membership, "conversation-service", "", NOW));
  }

  @Test
  void expiredFutureAndTenantlessTokensAreRejected() throws Exception {
    UUID user = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID membership = UUID.randomUUID();
    IdentityJwtVerifier verifier = verifier(Clock.fixed(NOW, ZoneOffset.UTC));

    assertInvalid(
        verifier,
        token(user, tenant, membership, "conversation-service", "", NOW.minusSeconds(400)));
    assertInvalid(
        verifier, token(user, tenant, membership, "conversation-service", "", NOW.plusSeconds(31)));
    assertInvalid(verifier, token(user, null, membership, "conversation-service", "", NOW));
  }

  @Test
  void staleOrReboundVerifierBundleFailsClosed() throws Exception {
    MutableClock clock = new MutableClock(NOW);
    IdentityJwtVerifier stale = verifier(clock);
    clock.advance(Duration.ofMinutes(6));
    assertThatThrownBy(
            () ->
                stale.verify(
                    token(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "conversation-service",
                        "",
                        NOW)))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.AUTHORIZATION_UNAVAILABLE));

    IdentityJwtVerifier verifier = verifier(Clock.fixed(NOW, ZoneOffset.UTC));
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    KeyPair replacement = generator.generateKeyPair();
    Files.writeString(
        bundle,
        "current_key_id=k1\nkey.k1="
            + Base64.getEncoder().encodeToString(replacement.getPublic().getEncoded())
            + "\n");
    assertThatThrownBy(verifier::refresh)
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.AUTHORIZATION_UNAVAILABLE));
  }

  private IdentityJwtVerifier verifier(Clock clock) {
    return new IdentityJwtVerifier(
        bundle, "https://identity.sajtech.internal", clock, Duration.ofMinutes(5));
  }

  private String token(
      UUID user, UUID tenant, UUID membership, String audience, String extra, Instant issuedAt)
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
            + issuedAt.getEpochSecond()
            + ",\"exp\":"
            + issuedAt.plusSeconds(300).getEpochSecond()
            + extra
            + "}";
    String encoded =
        BASE64_URL.encodeToString(header.getBytes(StandardCharsets.UTF_8))
            + "."
            + BASE64_URL.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
    Signature signature = Signature.getInstance("SHA256withRSA");
    signature.initSign(keys.getPrivate());
    signature.update(encoded.getBytes(StandardCharsets.US_ASCII));
    return encoded + "." + BASE64_URL.encodeToString(signature.sign());
  }

  private static void assertInvalid(IdentityJwtVerifier verifier, String token) {
    assertThatThrownBy(() -> verifier.verify(token))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.INVALID_ACCESS_TOKEN));
  }

  private static final class MutableClock extends Clock {
    private Instant value;

    private MutableClock(Instant value) {
      this.value = value;
    }

    void advance(Duration duration) {
      value = value.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC required");
      return this;
    }

    @Override
    public Instant instant() {
      return value;
    }
  }
}
