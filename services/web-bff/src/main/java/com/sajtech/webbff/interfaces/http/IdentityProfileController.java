package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.IdentityGateway;
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

  @PostMapping("/contacts")
  public Created add(@Valid @RequestBody Add body, HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return new Created(identity.addContact(s.refreshCredential(), body.type(), body.value()));
  }

  @PostMapping("/contacts/{id}/verify")
  public Verified verify(
      @PathVariable UUID id, @RequestBody Verify body, HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Verified(identity.verifyContact(session.refreshCredential(), id, body.code()));
  }

  @PostMapping("/contacts/{id}/primary")
  public Accepted primary(@PathVariable UUID id, HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(identity.setPrimaryContact(session.refreshCredential(), id));
  }

  @DeleteMapping("/contacts/{id}")
  public Accepted remove(@PathVariable UUID id, HttpServletRequest request) {
    BrowserSession session = HttpSupport.authenticated(request);
    return new Accepted(identity.removeContact(session.refreshCredential(), id));
  }

  public record Profile(UUID id, String firstName, String lastName, String fatherName) {}

  public record Contact(UUID id, String type, String value, boolean verified, boolean primary) {}

  public record Add(
      @NotBlank @Pattern(regexp = "EMAIL|PHONE") String type,
      @NotBlank @Size(max = 254) String value) {}

  public record Created(UUID id) {}

  public record Verify(String code) {}

  public record Verified(boolean verified) {}

  public record Accepted(boolean accepted) {}
}
