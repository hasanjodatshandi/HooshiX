package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.port.out.IdentityGateway;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/password")
public final class PasswordController {
  private final IdentityGateway identity;

  public PasswordController(IdentityGateway identity) {
    this.identity = identity;
  }

  @PostMapping("/change")
  public Accepted change(@Valid @RequestBody Change body) {
    return new Accepted(
        identity.changePassword(
            body.refreshCredential(), body.currentPassword(), body.newPassword()));
  }

  @PostMapping("/recovery/request")
  public Accepted request(@Valid @RequestBody RecoveryRequest body) {
    return new Accepted(identity.requestPasswordRecovery(body.contact()));
  }

  @PostMapping("/recovery/confirm")
  public Accepted confirm(@Valid @RequestBody RecoveryConfirm body) {
    return new Accepted(
        identity.confirmPasswordRecovery(body.contact(), body.code(), body.newPassword()));
  }

  public record Accepted(boolean accepted) {}

  public record Change(
      @NotBlank String refreshCredential,
      @NotBlank String currentPassword,
      @NotBlank String newPassword) {}

  public record RecoveryRequest(@NotBlank String contact) {}

  public record RecoveryConfirm(
      @NotBlank String contact, @NotBlank String code, @NotBlank String newPassword) {}
}
