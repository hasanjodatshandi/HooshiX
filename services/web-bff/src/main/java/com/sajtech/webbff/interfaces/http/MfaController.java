package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.model.BrowserSessionGrant;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.TrustedClientAddressPort;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!migration")
@RequestMapping("/api/v1/identity/mfa")
public final class MfaController {
  private final IdentityGateway identity;
  private final BrowserSessionPort sessions;
  private final TrustedClientAddressPort addresses;
  private final Clock clock;

  public MfaController(
      IdentityGateway identity,
      BrowserSessionPort sessions,
      TrustedClientAddressPort addresses,
      Clock clock) {
    this.identity = identity;
    this.sessions = sessions;
    this.addresses = addresses;
    this.clock = clock;
  }

  @GetMapping
  public MfaStatusResponse status(
      @RequestHeader("X-Request-Id") String requestId, HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    var status = identity.mfaStatus(HttpSupport.requestId(requestId), session.refreshCredential());
    return new MfaStatusResponse(status.totpEnabled(), status.recoveryCodesRemaining());
  }

  @PostMapping("/totp/enrollment")
  public TotpEnrollmentResponse startEnrollment(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody StartEnrollmentRequest body,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    var started =
        identity.startTotpEnrollment(
            HttpSupport.requestId(requestId),
            session.refreshCredential(),
            addresses.parse(clientIp),
            body.currentProof() == null ? null : proof(body.currentProof()));
    return new TotpEnrollmentResponse(
        started.enrollmentChallenge(),
        started.base32Secret(),
        started.otpauthUri(),
        started.expiresAt());
  }

  @PostMapping("/totp/enrollment/confirm")
  public RecoveryCodesResponse confirmEnrollment(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody ConfirmEnrollmentRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    var mutation =
        identity.confirmTotpEnrollment(
            HttpSupport.requestId(requestId),
            old.refreshCredential(),
            body.enrollmentChallenge(),
            body.totpCode(),
            addresses.parse(clientIp));
    rotate(old, mutation.session(), response);
    return new RecoveryCodesResponse(mutation.recoveryCodes());
  }

  @DeleteMapping("/totp")
  public void disable(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody ProofRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    var mutation =
        identity.disableTotp(
            HttpSupport.requestId(requestId),
            old.refreshCredential(),
            proof(body),
            addresses.parse(clientIp));
    rotate(old, mutation.session(), response);
  }

  @PostMapping("/recovery-codes/rotate")
  public RecoveryCodesResponse rotateRecoveryCodes(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody ProofRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    var mutation =
        identity.rotateRecoveryCodes(
            HttpSupport.requestId(requestId),
            old.refreshCredential(),
            proof(body),
            addresses.parse(clientIp));
    rotate(old, mutation.session(), response);
    return new RecoveryCodesResponse(mutation.recoveryCodes());
  }

  private void rotate(
      BrowserSession old,
      IdentityGateway.LoginResult identitySession,
      HttpServletResponse response) {
    BrowserSessionGrant grant =
        sessions.rotateSecurityState(
            old,
            identitySession.refreshCredential(),
            identitySession.idleExpiresAt(),
            identitySession.absoluteExpiresAt());
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
  }

  private static IdentityGateway.MfaProof proof(ProofRequest value) {
    return new IdentityGateway.MfaProof(value.type(), value.code());
  }

  public record ProofRequest(
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

  public record StartEnrollmentRequest(@Valid ProofRequest currentProof) {}

  public record ConfirmEnrollmentRequest(
      @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String enrollmentChallenge,
      @NotBlank @Pattern(regexp = "[0-9]{6}") String totpCode) {}

  public record MfaStatusResponse(boolean totpEnabled, int recoveryCodesRemaining) {}

  public record TotpEnrollmentResponse(
      String enrollmentChallenge, String base32Secret, String otpauthUri, Instant expiresAt) {}

  public record RecoveryCodesResponse(List<String> recoveryCodes) {
    public RecoveryCodesResponse {
      recoveryCodes = List.copyOf(recoveryCodes);
    }
  }
}
