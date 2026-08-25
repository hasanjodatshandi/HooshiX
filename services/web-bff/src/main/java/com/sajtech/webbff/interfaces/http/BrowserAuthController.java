package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.TrustedClientAddressPort;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public final class BrowserAuthController {
  private final BrowserSessionPort sessions;
  private final IdentityGateway identity;
  private final TrustedClientAddressPort addresses;
  private final Clock clock;

  public BrowserAuthController(
      BrowserSessionPort sessions,
      IdentityGateway identity,
      TrustedClientAddressPort addresses,
      Clock clock) {
    this.sessions = sessions;
    this.identity = identity;
    this.addresses = addresses;
    this.clock = clock;
  }

  @PostMapping("/session/bootstrap")
  public ResponseEntity<SessionResponse> bootstrap(
      HttpServletRequest request, HttpServletResponse response) {
    if (request.getAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE) != null)
      throw new BffException(BffError.INVALID_REQUEST, "Session already exists");
    BrowserSessionGrant grant = sessions.bootstrap();
    HttpSupport.setCookie(response, grant.cookieValue(), 600);
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new SessionResponse(grant.csrfToken(), grant.session().mode().name()));
  }

  @PostMapping("/session/csrf")
  public SessionResponse rotateCsrf(HttpServletRequest request, HttpServletResponse response) {
    BrowserSession old =
        (BrowserSession) request.getAttribute(BrowserSecurityContext.SESSION_ATTRIBUTE);
    BrowserSessionGrant grant;
    if (old == null) {
      grant = sessions.bootstrap();
    } else {
      grant =
          switch (old.mode()) {
            case PREAUTH -> {
              sessions.destroy(old);
              yield sessions.bootstrap();
            }
            case MFA_PREAUTH ->
                sessions.rotateMfaPreauth(
                    old,
                    old.userId(),
                    requiredChallenge(old.mfaChallenge()),
                    old.absoluteExpiresAt());
            case AUTHENTICATED_ONBOARDING, TENANT_AUTHENTICATED ->
                sessions.rotateSecurityState(
                    old, old.refreshCredential(), old.idleExpiresAt(), old.absoluteExpiresAt());
          };
    }
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return new SessionResponse(grant.csrfToken(), grant.session().mode().name());
  }

  @PostMapping("/local")
  public SessionResponse login(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody LocalLoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.session(request);
    if (old.mode() != BrowserSessionMode.PREAUTH && old.mode() != BrowserSessionMode.MFA_PREAUTH)
      throw new BffException(BffError.INVALID_REQUEST, "Pre-auth session is required");
    var result =
        identity.login(
            HttpSupport.requestId(requestId),
            body.channel(),
            body.contact(),
            body.password(),
            addresses.parse(clientIp));
    BrowserSessionGrant grant =
        switch (result.mode()) {
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
                  requiredContext(result.selectedTenantId()),
                  requiredContext(result.selectedMembershipId()));
          case MFA_REQUIRED ->
              sessions.rotateMfaPreauth(
                  old,
                  result.userId(),
                  requiredChallenge(result.mfaChallenge()),
                  clock.instant().plusSeconds(300));
          default ->
              throw new BffException(
                  BffError.DEPENDENCY_UNAVAILABLE, "Unexpected Identity session mode");
        };
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return new SessionResponse(grant.csrfToken(), grant.session().mode().name());
  }

  @PostMapping("/mfa/complete")
  public SessionResponse completeMfa(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody MfaProofRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.session(request);
    if (old.mode() != BrowserSessionMode.MFA_PREAUTH || old.mfaChallenge() == null) {
      throw new BffException(BffError.INVALID_REQUEST, "MFA pre-auth session is required");
    }
    var result =
        identity.completeMfaAuthentication(
            HttpSupport.requestId(requestId),
            old.mfaChallenge(),
            new IdentityGateway.MfaProof(body.type(), body.code()),
            addresses.parse(clientIp));
    BrowserSessionGrant grant = rotateCompleted(old, result);
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return new SessionResponse(grant.csrfToken(), grant.session().mode().name());
  }

  private BrowserSessionGrant rotateCompleted(
      BrowserSession old, IdentityGateway.LoginResult result) {
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
              requiredContext(result.selectedTenantId()),
              requiredContext(result.selectedMembershipId()));
      case MFA_REQUIRED ->
          throw new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity returned an incomplete MFA session");
    };
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @RequestHeader("X-Request-Id") String requestId,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession s = HttpSupport.authenticated(request);
    BffException failure = null;
    try {
      identity.logout(HttpSupport.requestId(requestId), s.refreshCredential());
    } catch (BffException e) {
      failure = e;
    } finally {
      sessions.destroy(s);
      HttpSupport.clearCookie(response);
    }
    if (failure != null) throw failure;
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/session")
  public Map<String, Object> session(HttpServletRequest request) {
    BrowserSession s = HttpSupport.session(request);
    return Map.of(
        "mode",
        s.mode().name(),
        "authenticated",
        s.authenticated(),
        "tenantSelected",
        s.tenantAuthenticated());
  }

  private static java.util.UUID requiredContext(java.util.UUID value) {
    if (value == null)
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Identity tenant context is incomplete");
    return value;
  }

  private static String requiredChallenge(String value) {
    if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity MFA challenge is invalid");
    }
    return value;
  }

  public record LocalLoginRequest(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact,
      @NotNull @Size(min = 1, max = 4096) String password) {}

  public record MfaProofRequest(
      @NotBlank @Pattern(regexp = "TOTP|RECOVERY_CODE") String type,
      @NotBlank @Pattern(regexp = "(?:[0-9]{6}|[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3})") String code) {
    @AssertTrue(message = "MFA proof type and code shape must agree")
    public boolean isConsistent() {
      return ("TOTP".equals(type) && code != null && code.matches("[0-9]{6}"))
          || ("RECOVERY_CODE".equals(type)
              && code != null
              && code.matches("[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}"));
    }
  }

  public record SessionResponse(String csrfToken, String mode) {}
}
