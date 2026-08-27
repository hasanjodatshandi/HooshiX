package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!migration")
@RequestMapping("/api/v1/identity/erasure")
public final class ErasureController {
  private final IdentityGateway identity;
  private final BrowserSessionPort sessions;

  public ErasureController(IdentityGateway identity, BrowserSessionPort sessions) {
    this.identity = identity;
    this.sessions = sessions;
  }

  @PostMapping
  public ResponseEntity<ErasureAccepted> request(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody ErasureBody body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession session = HttpSupport.authenticated(request);
    var result =
        identity.requestSelfErasure(
            HttpSupport.requestId(requestId),
            session.refreshCredential(),
            body.confirmation(),
            body.mfaProof() == null
                ? null
                : new IdentityGateway.MfaProof(body.mfaProof().type(), body.mfaProof().code()));
    sessions.eraseUser(session.userId());
    HttpSupport.clearCookie(response);
    return ResponseEntity.accepted()
        .body(
            new ErasureAccepted(
                result.erasureRequestId().toString(),
                result.state(),
                result.participantPolicyVersion()));
  }

  public record ErasureBody(
      @NotBlank @Pattern(regexp = "ERASE_MY_ACCOUNT") String confirmation, @Valid Proof mfaProof) {}

  public record Proof(
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

  public record ErasureAccepted(
      String erasureRequestId, String state, String participantPolicyVersion) {}
}
