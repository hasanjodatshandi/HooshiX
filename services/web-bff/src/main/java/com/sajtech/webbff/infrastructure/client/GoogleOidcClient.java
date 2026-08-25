package com.sajtech.webbff.infrastructure.client;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.VerifiedGoogleIdentity;
import com.sajtech.webbff.application.port.out.GoogleOidcProvider;
import com.sajtech.webbff.configuration.WebBffProperties;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;

public final class GoogleOidcClient implements GoogleOidcProvider {
  private static final String ISSUER = "https://accounts.google.com";
  private final WebBffProperties properties;
  private final RestClient rest;
  private final JwtDecoder decoder;
  private final Semaphore admission;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final Clock clock;
  private final AtomicInteger inFlight = new AtomicInteger();

  public GoogleOidcClient(WebBffProperties properties) {
    this(properties, ObservationRegistry.NOOP, new SimpleMeterRegistry(), Clock.systemUTC());
  }

  public GoogleOidcClient(
      WebBffProperties properties, ObservationRegistry observations, MeterRegistry meters) {
    this(properties, observations, meters, Clock.systemUTC());
  }

  public GoogleOidcClient(
      WebBffProperties properties,
      ObservationRegistry observations,
      MeterRegistry meters,
      Clock clock) {
    this.properties = properties;
    this.observations = observations;
    this.meters = meters;
    this.clock = clock;
    this.admission = new Semaphore(properties.googleMaximumConcurrentCalls(), true);
    Gauge.builder("web_bff.oidc.provider.in_flight", inFlight, AtomicInteger::get).register(meters);
    JdkClientHttpRequestFactory requests = requestFactory();
    this.rest = RestClient.builder().requestFactory(requests).build();
    RestTemplate jwtRest = new RestTemplate(requests);
    NimbusJwtDecoder jwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(properties.googleJwkSetUri().toString())
            .restOperations(jwtRest)
            .build();
    OAuth2TokenValidator<Jwt> issuerAndTime = JwtValidators.createDefaultWithIssuer(ISSUER);
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            "aud", values -> values != null && values.contains(properties.googleClientId()));
    jwtDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTime, audience));
    this.decoder = jwtDecoder;
  }

  @Override
  public URI authorizationUri(
      String state, String nonce, String codeChallenge, String redirectUri) {
    requireToken(state, "state");
    requireToken(nonce, "nonce");
    requireToken(codeChallenge, "code challenge");
    return UriComponentsBuilder.fromUri(properties.googleAuthorizationEndpoint())
        .queryParam("client_id", properties.googleClientId())
        .queryParam("redirect_uri", redirectUri)
        .queryParam("response_type", "code")
        .queryParam("scope", "openid email profile")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", codeChallenge)
        .queryParam("code_challenge_method", "S256")
        .queryParam("access_type", "online")
        .queryParam("prompt", "select_account")
        .build()
        .encode(StandardCharsets.UTF_8)
        .toUri();
  }

  @Override
  public VerifiedGoogleIdentity exchangeAndValidate(
      String code, String verifier, String expectedNonce, String redirectUri) {
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    Observation observation = startObservation();
    String outcome = "INTERNAL";
    try {
      VerifiedGoogleIdentity verified =
          exchangeAndValidateInternal(code, verifier, expectedNonce, redirectUri);
      outcome = "SUCCESS";
      return verified;
    } catch (BffException exception) {
      outcome = exception.error().name();
      throw exception;
    } finally {
      inFlight.decrementAndGet();
      stopObservation(observation, outcome);
      record(outcome, started);
    }
  }

  private VerifiedGoogleIdentity exchangeAndValidateInternal(
      String code, String verifier, String expectedNonce, String redirectUri) {
    if (!properties.googleOidcEnabled()) {
      throw unavailable();
    }
    if (code == null
        || !code.matches("[A-Za-z0-9._~+/-]{1,2048}")
        || verifier == null
        || !verifier.matches("[A-Za-z0-9_-]{43}")
        || expectedNonce == null
        || !expectedNonce.matches("[A-Za-z0-9_-]{43}")) {
      throw invalid();
    }
    if (!admission.tryAcquire()) throw unavailable();
    String secret = null;
    try {
      secret = clientSecret();
      var form = new LinkedMultiValueMap<String, String>();
      form.add("code", code);
      form.add("client_id", properties.googleClientId());
      form.add("client_secret", secret);
      form.add("redirect_uri", redirectUri);
      form.add("grant_type", "authorization_code");
      form.add("code_verifier", verifier);
      TokenResponse token =
          rest.post()
              .uri(properties.googleTokenEndpoint())
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(TokenResponse.class);
      if (token == null || token.idToken() == null || token.idToken().length() > 8192) {
        throw invalid();
      }
      Jwt jwt = decoder.decode(token.idToken());
      Instant now = clock.instant();
      Instant issuedAt = jwt.getIssuedAt();
      if (issuedAt == null
          || issuedAt.isAfter(now.plusSeconds(30))
          || issuedAt.plus(Duration.ofMinutes(10)).isBefore(now)) {
        throw invalid();
      }
      List<String> audiences = jwt.getAudience();
      String authorizedParty = jwt.getClaimAsString("azp");
      if (audiences == null
          || audiences.isEmpty()
          || (authorizedParty != null && !properties.googleClientId().equals(authorizedParty))
          || (audiences.size() > 1 && authorizedParty == null)) {
        throw invalid();
      }
      String nonce = jwt.getClaimAsString("nonce");
      if (!constantTime(expectedNonce, nonce)) throw invalid();
      String subject = jwt.getSubject();
      if (subject == null || !subject.matches("[A-Za-z0-9_-]{1,255}")) throw invalid();
      String email = bounded(jwt.getClaimAsString("email"), 254);
      boolean emailVerified = Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"));
      String given = bounded(jwt.getClaimAsString("given_name"), 480);
      String family = bounded(jwt.getClaimAsString("family_name"), 480);
      return new VerifiedGoogleIdentity(ISSUER, subject, email, emailVerified, given, family);
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().is4xxClientError()) throw invalid();
      throw unavailable();
    } catch (JwtException | IllegalArgumentException | ClassCastException exception) {
      throw invalid();
    } catch (RestClientException exception) {
      throw unavailable();
    } finally {
      secret = null;
      admission.release();
    }
  }

  private String clientSecret() {
    Path path = properties.googleClientSecretPath();
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4096) {
        throw unavailable();
      }
      String value = Files.readString(path, StandardCharsets.UTF_8).strip();
      if (value.isEmpty()
          || value.length() > 2048
          || value.codePoints().anyMatch(Character::isISOControl)) throw unavailable();
      return value;
    } catch (IOException exception) {
      throw unavailable();
    }
  }

  private static JdkClientHttpRequestFactory requestFactory() {
    HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofMillis(1200));
    return factory;
  }

  private static String bounded(String value, int maximumBytes) {
    if (value == null) return null;
    if (value.isBlank()
        || value.getBytes(StandardCharsets.UTF_8).length > maximumBytes
        || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
    return value;
  }

  private static void requireToken(String value, String name) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalArgumentException("OIDC " + name + " is invalid");
    }
  }

  private static boolean constantTime(String expected, String actual) {
    if (actual == null) return false;
    return java.security.MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
  }

  private static BffException invalid() {
    return new BffException(BffError.OIDC_INVALID_RESPONSE, "OIDC provider response is invalid");
  }

  private static BffException unavailable() {
    return new BffException(BffError.OIDC_UNAVAILABLE, "OIDC provider is unavailable");
  }

  private Observation startObservation() {
    try {
      return Observation.start("web_bff.oidc.provider", observations);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static void stopObservation(Observation observation, String outcome) {
    if (observation == null) return;
    try {
      observation.lowCardinalityKeyValue("provider", "google");
      observation.lowCardinalityKeyValue("operation", "TOKEN_EXCHANGE");
      observation.lowCardinalityKeyValue("outcome", outcome);
      observation.stop();
    } catch (RuntimeException ignored) {
    }
  }

  private void record(String outcome, long started) {
    try {
      io.micrometer.core.instrument.Timer.builder("web_bff.oidc.provider.duration")
          .tag("provider", "google")
          .tag("operation", "TOKEN_EXCHANGE")
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
  }

  private record TokenResponse(
      @com.fasterxml.jackson.annotation.JsonProperty("id_token") String idToken) {}
}
