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

  @PostMapping("/local")
  public SessionResponse login(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody LocalLoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.session(request);
    if (old.mode() != BrowserSessionMode.PREAUTH)
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

  public record LocalLoginRequest(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact,
      @NotNull @Size(min = 1, max = 4096) String password) {}

  public record SessionResponse(String csrfToken, String mode) {}
}
