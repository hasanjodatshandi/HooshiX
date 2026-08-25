package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.*;
import com.sajtech.webbff.interfaces.validation.UnicodeCodePointSize;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/password")
public final class PasswordController {
  private final IdentityGateway identity;
  private final BrowserSessionPort sessions;
  private final TrustedClientAddressPort addresses;
  private final Clock clock;

  public PasswordController(
      IdentityGateway identity,
      BrowserSessionPort sessions,
      TrustedClientAddressPort addresses,
      Clock clock) {
    this.identity = identity;
    this.sessions = sessions;
    this.addresses = addresses;
    this.clock = clock;
  }

  @PostMapping("/change")
  public ResponseEntity<PasswordChanged> change(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody Change body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    var changed =
        identity.changePassword(
            HttpSupport.requestId(requestId),
            old.refreshCredential(),
            body.currentPassword(),
            body.newPassword());
    BrowserSessionGrant grant =
        sessions.rotateSecurityState(
            old, changed.refreshCredential(), changed.idleExpiresAt(), changed.absoluteExpiresAt());
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return ResponseEntity.ok(new PasswordChanged(true, grant.csrfToken()));
  }

  @PostMapping("/recovery/request")
  public Accepted request(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody RecoveryRequest body) {
    return new Accepted(
        identity.requestPasswordRecovery(
            HttpSupport.requestId(requestId),
            body.channel(),
            body.contact(),
            addresses.parse(clientIp)));
  }

  @PostMapping("/recovery/confirm")
  public Accepted confirm(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody RecoveryConfirm body) {
    return new Accepted(
        identity.confirmPasswordRecovery(
            HttpSupport.requestId(requestId),
            body.channel(),
            body.contact(),
            body.code(),
            body.newPassword(),
            addresses.parse(clientIp)));
  }

  public record Accepted(boolean accepted) {}

  public record PasswordChanged(boolean changed, String csrfToken) {}

  public record Change(
      @NotNull @Size(min = 1, max = 4096) String currentPassword,
      @NotNull @UnicodeCodePointSize(min = 12, max = 128) String newPassword) {}

  public record RecoveryRequest(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact) {}

  public record RecoveryConfirm(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact,
      @NotBlank @Pattern(regexp = "[0-9]{8}") String code,
      @NotNull @UnicodeCodePointSize(min = 12, max = 128) String newPassword) {}
}
