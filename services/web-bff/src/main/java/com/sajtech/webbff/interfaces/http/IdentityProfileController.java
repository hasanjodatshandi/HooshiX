package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.interfaces.validation.UnicodeCodePointSize;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
public final class IdentityProfileController {
  private final IdentityGateway identity;

  public IdentityProfileController(IdentityGateway identity) {
    this.identity = identity;
  }

  @GetMapping("/profile")
  public Profile profile(HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    var p = identity.profile(s.refreshCredential());
    return new Profile(p.id(), p.firstName(), p.lastName(), p.fatherName());
  }

  @GetMapping("/contacts")
  public List<Contact> contacts(HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return identity.contacts(s.refreshCredential()).stream()
        .map(c -> new Contact(c.id(), c.type(), c.value(), c.verified(), c.primary()))
        .toList();
  }

  @PutMapping("/profile")
  public Accepted update(
      @RequestHeader("X-Request-ID") String requestId,
      @Valid @RequestBody UpdateProfile body,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(
        identity.updateProfile(
            HttpSupport.requestId(requestId),
            session.refreshCredential(),
            body.firstName(),
            body.lastName(),
            body.fatherName()));
  }

  @PostMapping("/contacts")
  public Created add(
      @RequestHeader("X-Request-ID") String requestId,
      @Valid @RequestBody Add body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return new Created(
        identity.addContact(
            HttpSupport.requestId(requestId),
            s.refreshCredential(),
            body.type(),
            body.value(),
            body.locale()));
  }

  @PostMapping("/contacts/{id}/resend")
  public Accepted resend(
      @RequestHeader("X-Request-ID") String requestId,
      @PathVariable String id,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(
        identity.resendContactVerification(
            HttpSupport.requestId(requestId), session.refreshCredential(), HttpSupport.id(id)));
  }

  @PostMapping("/contacts/{id}/verify")
  public Verified verify(
      @RequestHeader("X-Request-ID") String requestId,
      @PathVariable String id,
      @Valid @RequestBody Verify body,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Verified(
        identity.verifyContact(
            HttpSupport.requestId(requestId),
            session.refreshCredential(),
            HttpSupport.id(id),
            body.code()));
  }

  @PostMapping("/contacts/{id}/primary")
  public Accepted primary(
      @RequestHeader("X-Request-ID") String requestId,
      @PathVariable String id,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(
        identity.setPrimaryContact(
            HttpSupport.requestId(requestId), session.refreshCredential(), HttpSupport.id(id)));
  }

  @DeleteMapping("/contacts/{id}")
  public Accepted remove(
      @RequestHeader("X-Request-ID") String requestId,
      @PathVariable String id,
      HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(
        identity.removeContact(
            HttpSupport.requestId(requestId), session.refreshCredential(), HttpSupport.id(id)));
  }

  public record Profile(UUID id, String firstName, String lastName, String fatherName) {}

  public record Contact(UUID id, String type, String value, boolean verified, boolean primary) {}

  public record UpdateProfile(
      @NotBlank @UnicodeCodePointSize(max = 120) String firstName,
      @NotBlank @UnicodeCodePointSize(max = 120) String lastName,
      @UnicodeCodePointSize(max = 120) String fatherName) {}

  public record Add(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String type,
      @NotBlank @Size(max = 254) String value,
      @NotBlank @Pattern(regexp = "en|fa") String locale) {}

  public record Created(UUID id) {}

  public record Verify(@NotBlank @Pattern(regexp = "[0-9]{8}") String code) {}

  public record Verified(boolean verified) {}

  public record Accepted(boolean accepted) {}
}
