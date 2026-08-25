package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class OidcController {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final String CALLBACK_PATH = "/api/v1/auth/oidc/google/callback";
  private static final String GOOGLE_ISSUER = "https://accounts.google.com";

  private final OidcPreauthPort preauth;
  private final GoogleOidcProvider provider;
  private final IdentityGateway identity;
  private final BrowserSessionPort sessions;
  private final TrustedClientAddressPort addresses;
  private final OidcQuotaPort quota;
  private final com.sajtech.webbff.configuration.WebBffProperties properties;
  private final Clock clock;

  public OidcController(
      OidcPreauthPort preauth,
      GoogleOidcProvider provider,
      IdentityGateway identity,
      BrowserSessionPort sessions,
      TrustedClientAddressPort addresses,
      OidcQuotaPort quota,
      com.sajtech.webbff.configuration.WebBffProperties properties,
      Clock clock) {
    this.preauth = preauth;
    this.provider = provider;
    this.identity = identity;
    this.sessions = sessions;
    this.addresses = addresses;
    this.quota = quota;
    this.properties = properties;
    this.clock = clock;
  }

  @PostMapping("/api/v1/auth/oidc/google/start")
  public OidcStartResponse startLogin(
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody OidcStartRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession session = HttpSupport.session(request);
    if (session.mode() != BrowserSessionMode.PREAUTH
        && session.mode() != BrowserSessionMode.MFA_PREAUTH) {
      throw new BffException(BffError.INVALID_REQUEST, "Pre-auth session is required");
    }
    return start(OidcPurpose.LOGIN, body.returnTarget(), session, clientIp, request, response);
  }

  @PostMapping("/api/v1/identity/external-identities/google/start")
  public OidcStartResponse startLink(
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody OidcStartRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession session = HttpSupport.authenticated(request);
    return start(OidcPurpose.LINK, body.returnTarget(), session, clientIp, request, response);
  }

  @GetMapping(CALLBACK_PATH)
  public ResponseEntity<Void> callback(
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.session(request);
    byte[] parsedAddress = addresses.parse(clientIp);
    quota.consume(OidcQuotaPort.Operation.OIDC_CALLBACK, parsedAddress);
    try {
      String state = exactlyOneStateQuery(request);
      String preauthCookie =
          HttpSupport.cookie(request, BrowserSecurityContext.OIDC_PREAUTH_COOKIE_NAME);
      OidcPreauthTransaction transaction =
          preauth
              .consume(preauthCookie, state)
              .orElseThrow(
                  () ->
                      new BffException(
                          BffError.OIDC_STATE_INVALID, "OIDC state is invalid or expired"));
      if (!old.locator().equals(transaction.browserSessionLocator())
          || !callbackUri().equals(transaction.redirectUri())) {
        throw new BffException(BffError.OIDC_STATE_INVALID, "OIDC binding is invalid");
      }
      if ((transaction.purpose() == OidcPurpose.LOGIN
              && old.mode() != BrowserSessionMode.PREAUTH
              && old.mode() != BrowserSessionMode.MFA_PREAUTH)
          || (transaction.purpose() == OidcPurpose.LINK && !old.authenticated())) {
        throw new BffException(BffError.OIDC_STATE_INVALID, "OIDC session state is invalid");
      }
      String providerError = optionalSingleQuery(request, "error");
      if (providerError != null) {
        throw new BffException(BffError.OIDC_INVALID_RESPONSE, "OIDC provider rejected request");
      }
      String code = exactlyOneQuery(request, "code");
      VerifiedGoogleIdentity verified =
          provider.exchangeAndValidate(
              code, transaction.verifier(), transaction.nonce(), transaction.redirectUri());
      byte[] evidenceId = evidenceId();
      IdentityGateway.LoginResult result;
      try {
        result =
            transaction.purpose() == OidcPurpose.LOGIN
                ? identity.establishExternalIdentity(
                    UUID.randomUUID(), evidenceId, clock.instant(), verified, parsedAddress)
                : identity.linkExternalIdentity(
                    UUID.randomUUID(),
                    old.refreshCredential(),
                    evidenceId,
                    clock.instant(),
                    verified,
                    parsedAddress);
      } finally {
        java.util.Arrays.fill(evidenceId, (byte) 0);
      }
      BrowserSessionGrant grant = rotate(old, result, transaction.purpose());
      HttpSupport.setCookie(
          response,
          grant.cookieValue(),
          HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
      URI target =
          URI.create(
              result.mode() == IdentityGateway.SessionMode.MFA_REQUIRED
                  ? "/login/mfa"
                  : transaction.returnTarget());
      return ResponseEntity.status(HttpStatus.SEE_OTHER).location(target).build();
    } finally {
      HttpSupport.clearOidcPreauthCookie(response);
    }
  }

  @GetMapping("/api/v1/identity/external-identities")
  public ExternalIdentityStatus status(
      @RequestHeader("X-Request-Id") String requestId, HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new ExternalIdentityStatus(
        identity.googleIdentityLinked(
            HttpSupport.requestId(requestId), session.refreshCredential()));
  }

  @DeleteMapping("/api/v1/identity/external-identities/google")
  public ResponseEntity<Void> unlink(
      @RequestHeader("X-Request-Id") String requestId,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    IdentityGateway.LoginResult result =
        identity.unlinkExternalIdentity(HttpSupport.requestId(requestId), old.refreshCredential());
    BrowserSessionGrant grant =
        sessions.rotateSecurityState(
            old, result.refreshCredential(), result.idleExpiresAt(), result.absoluteExpiresAt());
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return ResponseEntity.noContent().build();
  }

  private OidcStartResponse start(
      OidcPurpose purpose,
      String returnTarget,
      BrowserSession session,
      String clientIp,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (!properties.googleOidcEnabled()) {
      throw new BffException(BffError.OIDC_UNAVAILABLE, "Google OIDC is disabled");
    }
    quota.consume(OidcQuotaPort.Operation.OIDC_START, addresses.parse(clientIp));
    String target = validateReturnTarget(returnTarget);
    OidcAuthorizationStart started =
        preauth.begin(
            HttpSupport.cookie(request, BrowserSecurityContext.OIDC_PREAUTH_COOKIE_NAME),
            purpose,
            session.locator(),
            callbackUri(),
            target);
    URI authorization =
        provider.authorizationUri(
            started.state(), started.nonce(), started.codeChallenge(), callbackUri());
    HttpSupport.setOidcPreauthCookie(
        response,
        started.preauthCookie(),
        Math.max(0, java.time.Duration.between(clock.instant(), started.expiresAt()).toSeconds()));
    return new OidcStartResponse(authorization.toString(), started.expiresAt());
  }

  private BrowserSessionGrant rotate(
      BrowserSession old, IdentityGateway.LoginResult result, OidcPurpose purpose) {
    if (purpose == OidcPurpose.LINK) {
      if (result.mode() == IdentityGateway.SessionMode.MFA_REQUIRED) {
        throw new BffException(
            BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid link session");
      }
      return sessions.rotateSecurityState(
          old, result.refreshCredential(), result.idleExpiresAt(), result.absoluteExpiresAt());
    }
    return switch (result.mode()) {
      case AUTHENTICATED_ONBOARDING ->
          sessions.rotateAuthenticated(
              old,
              result.userId(),
              result.identitySessionId(),
              result.refreshFamilyId(),
              result.refreshCredential(),
              result.idleExpiresAt(),
              result.absoluteExpiresAt());
      case TENANT_AUTHENTICATED ->
          sessions.rotateAuthenticatedTenant(
              old,
              result.userId(),
              result.identitySessionId(),
              result.refreshFamilyId(),
              result.refreshCredential(),
              result.idleExpiresAt(),
              result.absoluteExpiresAt(),
              required(result.selectedTenantId()),
              required(result.selectedMembershipId()));
      case MFA_REQUIRED ->
          sessions.rotateMfaPreauth(
              old,
              result.userId(),
              required(result.mfaChallenge()),
              clock.instant().plusSeconds(300));
    };
  }

  private String callbackUri() {
    URI origin = properties.publicOrigin();
    return origin.getScheme()
        + "://"
        + origin.getHost()
        + (origin.getPort() == -1 ? "" : ":" + origin.getPort())
        + CALLBACK_PATH;
  }

  static String validateReturnTarget(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 1024
        || !value.startsWith("/")
        || value.startsWith("//")
        || value.indexOf('\\') >= 0
        || value.indexOf('#') >= 0
        || value.indexOf('?') >= 0
        || value.indexOf('%') >= 0
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new BffException(BffError.INVALID_REQUEST, "OIDC return target is invalid");
    }
    URI parsed = URI.create(value);
    if (parsed.isAbsolute()
        || parsed.getRawAuthority() != null
        || parsed.getRawUserInfo() != null
        || parsed.getRawQuery() != null
        || parsed.getRawFragment() != null
        || !value.equals(parsed.normalize().toString())) {
      throw new BffException(BffError.INVALID_REQUEST, "OIDC return target is invalid");
    }
    return value;
  }

  private static String exactlyOneQuery(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    if (values == null || values.length != 1 || values[0] == null || values[0].isBlank()) {
      throw new BffException(BffError.OIDC_INVALID_RESPONSE, "OIDC query is invalid");
    }
    return values[0];
  }

  private static String exactlyOneStateQuery(HttpServletRequest request) {
    String[] values = request.getParameterValues("state");
    if (values == null || values.length != 1 || values[0] == null || values[0].isBlank()) {
      throw new BffException(BffError.OIDC_STATE_INVALID, "OIDC state is invalid");
    }
    return values[0];
  }

  private static String optionalSingleQuery(HttpServletRequest request, String name) {
    String[] values = request.getParameterValues(name);
    if (values == null) return null;
    if (values.length != 1 || values[0] == null || values[0].isBlank()) {
      throw new BffException(BffError.OIDC_INVALID_RESPONSE, "OIDC query is invalid");
    }
    return values[0];
  }

  private static byte[] evidenceId() {
    byte[] value = new byte[32];
    RANDOM.nextBytes(value);
    return value;
  }

  private static UUID required(UUID value) {
    if (value == null) {
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity context is incomplete");
    }
    return value;
  }

  private static String required(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity challenge is invalid");
    }
    return value;
  }

  public record OidcStartRequest(@NotBlank @Size(max = 1024) String returnTarget) {}

  public record OidcStartResponse(String authorizationUrl, java.time.Instant expiresAt) {}

  public record ExternalIdentityStatus(boolean googleLinked) {}
}
