package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.TrustedClientAddressPort;
import com.sajtech.webbff.interfaces.validation.UnicodeCodePointSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!migration")
@RequestMapping("/api/v1/identity/registration")
public final class RegistrationController {
  private final IdentityGateway identity;
  private final TrustedClientAddressPort addresses;

  public RegistrationController(IdentityGateway identity, TrustedClientAddressPort addresses) {
    this.identity = identity;
    this.addresses = addresses;
  }

  @PostMapping
  public ResponseEntity<Accepted> register(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody Register body) {
    boolean accepted =
        identity
            .register(
                HttpSupport.requestId(requestId),
                body.channel(),
                body.contact(),
                body.password(),
                body.locale(),
                body.firstName(),
                body.lastName(),
                body.fatherName(),
                addresses.parse(clientIp))
            .accepted();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Accepted(accepted));
  }

  @PostMapping("/resend")
  public ResponseEntity<Accepted> resend(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody Resend body) {
    boolean accepted =
        identity.resendRegistration(
            HttpSupport.requestId(requestId),
            body.channel(),
            body.contact(),
            addresses.parse(clientIp));
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Accepted(accepted));
  }

  @PostMapping("/confirm")
  public Confirmed confirm(
      @RequestHeader("X-Request-Id") String requestId,
      @RequestHeader("X-HooshiX-Client-IP") String clientIp,
      @Valid @RequestBody Confirm body) {
    return new Confirmed(
        identity.confirmRegistration(
            HttpSupport.requestId(requestId),
            body.channel(),
            body.contact(),
            body.code(),
            addresses.parse(clientIp)));
  }

  public record Register(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact,
      @NotNull @UnicodeCodePointSize(min = 12, max = 128) String password,
      @NotBlank @Pattern(regexp = "fa|en") String locale,
      @NotBlank @UnicodeCodePointSize(max = 120) String firstName,
      @NotBlank @UnicodeCodePointSize(max = 120) String lastName,
      @UnicodeCodePointSize(max = 120) String fatherName) {}

  public record Resend(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact) {}

  public record Confirm(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String channel,
      @NotBlank @Size(max = 254) String contact,
      @NotBlank @Pattern(regexp = "[0-9]{8}") String code) {}

  public record Accepted(boolean accepted) {}

  public record Confirmed(boolean confirmed) {}
}
