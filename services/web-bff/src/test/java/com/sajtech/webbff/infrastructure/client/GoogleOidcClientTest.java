package com.sajtech.webbff.infrastructure.client;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.configuration.WebBffProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class GoogleOidcClientTest {
  private static final String CLIENT_ID = "client.apps.googleusercontent.com";
  @TempDir Path temp;
  private HttpServer server;
  private RSAKey key;
  private AtomicReference<String> token;
  private GoogleOidcClient client;

  @BeforeEach
  void setUp() throws Exception {
    key = new RSAKeyGenerator(2048).keyID("test-key").generate();
    token = new AtomicReference<>();
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/certs",
        exchange -> {
          byte[] body =
              ("{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/token",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              ("{\"access_token\":\"provider-secret\",\"expires_in\":3600,"
                      + "\"token_type\":\"Bearer\",\"id_token\":\""
                      + token.get()
                      + "\"}")
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    Path secret = temp.resolve("google-client-secret");
    Files.writeString(secret, "client-secret\n");
    WebBffProperties properties = mock(WebBffProperties.class);
    when(properties.googleOidcEnabled()).thenReturn(true);
    when(properties.googleClientId()).thenReturn(CLIENT_ID);
    when(properties.googleClientSecretPath()).thenReturn(secret);
    when(properties.googleMaximumConcurrentCalls()).thenReturn(2);
    when(properties.googleTokenEndpoint()).thenReturn(uri("/token"));
    when(properties.googleJwkSetUri()).thenReturn(uri("/certs"));
    client = new GoogleOidcClient(properties);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void validatesSignatureIssuerAudienceTimeNonceAndReturnsOnlyBoundedClaims() throws Exception {
    token.set(idToken(CLIENT_ID, "n".repeat(43), Instant.now().plusSeconds(300)));

    var result =
        client.exchangeAndValidate(
            "authorization-code",
            "v".repeat(43),
            "n".repeat(43),
            "https://app.example.test/api/v1/auth/oidc/google/callback");

    assertThat(result.issuer()).isEqualTo("https://accounts.google.com");
    assertThat(result.subject()).isEqualTo("google-subject");
    assertThat(result.email()).isEqualTo("person@example.com");
    assertThat(result.emailVerified()).isTrue();
    assertThat(result.givenName()).isEqualTo("Google");
    assertThat(result.familyName()).isEqualTo("Person");
  }

  @Test
  void rejectsNonceOrAudienceMismatchAsInvalidProviderEvidence() throws Exception {
    token.set(
        idToken(
            "different.apps.googleusercontent.com", "other-nonce", Instant.now().plusSeconds(300)));

    assertThatThrownBy(
            () ->
                client.exchangeAndValidate(
                    "authorization-code",
                    "v".repeat(43),
                    "n".repeat(43),
                    "https://app.example.test/api/v1/auth/oidc/google/callback"))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.OIDC_INVALID_RESPONSE));
  }

  @Test
  void rejectsStaleIssuedAt() throws Exception {
    token.set(
        idToken(
            List.of(CLIENT_ID),
            null,
            "n".repeat(43),
            Instant.now().minus(Duration.ofMinutes(11)),
            Instant.now().plusSeconds(300)));

    assertInvalid();
  }

  @Test
  void rejectsInvalidAuthorizedPartyForMultipleAudiences() throws Exception {
    token.set(
        idToken(
            List.of(CLIENT_ID, "second-audience"),
            "different.apps.googleusercontent.com",
            "n".repeat(43),
            Instant.now().minusSeconds(5),
            Instant.now().plusSeconds(300)));

    assertInvalid();
  }

  private void assertInvalid() {
    assertThatThrownBy(
            () ->
                client.exchangeAndValidate(
                    "authorization-code",
                    "v".repeat(43),
                    "n".repeat(43),
                    "https://app.example.test/api/v1/auth/oidc/google/callback"))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.OIDC_INVALID_RESPONSE));
  }

  private String idToken(String audience, String nonce, Instant expiry) throws Exception {
    return idToken(List.of(audience), null, nonce, Instant.now().minusSeconds(5), expiry);
  }

  private String idToken(
      List<String> audiences,
      String authorizedParty,
      String nonce,
      Instant issuedAt,
      Instant expiry)
      throws Exception {
    JWTClaimsSet claims =
        new JWTClaimsSet.Builder()
            .issuer("https://accounts.google.com")
            .subject("google-subject")
            .audience(audiences)
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiry))
            .claim("nonce", nonce)
            .claim("azp", authorizedParty)
            .claim("email", "person@example.com")
            .claim("email_verified", true)
            .claim("given_name", "Google")
            .claim("family_name", "Person")
            .build();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
  }
}
