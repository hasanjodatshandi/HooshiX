package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.model.BrowserSession;
import com.sajtech.webbff.application.port.out.AuthorizationGateway;
import com.sajtech.webbff.application.port.out.AuthorizationGateway.*;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/authorization")
public final class AuthorizationController {
  private final IdentityGateway identity;
  private final AuthorizationGateway authorization;

  public AuthorizationController(IdentityGateway identity, AuthorizationGateway authorization) {
    this.identity = identity;
    this.authorization = authorization;
  }

  @GetMapping("/permissions")
  public List<PermissionDto> permissions(
      @RequestParam(defaultValue = "50") int pageSize,
      @RequestParam(required = false) String pageToken,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.permissions(token(s), pageSize, pageToken);
  }

  @GetMapping("/roles")
  public RolePage roles(
      @RequestParam(defaultValue = "50") int pageSize,
      @RequestParam(required = false) String pageToken,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.roles(token(s), pageSize, pageToken);
  }

  @GetMapping("/roles/{roleId}")
  public RoleDto role(@PathVariable String roleId, HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.role(token(s), HttpSupport.id(roleId));
  }

  @GetMapping("/memberships/{membershipId}")
  public MembershipAuthorizationDto membership(
      @PathVariable String membershipId, HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.membership(token(s), HttpSupport.id(membershipId));
  }

  @PostMapping("/roles")
  public RoleDto create(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody CreateRole body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.createRole(
        token(s),
        HttpSupport.requestId(requestId),
        body.name(),
        body.description(),
        body.permissionKeys());
  }

  @PutMapping("/roles/{roleId}")
  public RoleDto update(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String roleId,
      @Valid @RequestBody UpdateRole body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.updateRole(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(roleId),
        body.expectedVersion(),
        body.name(),
        body.description());
  }

  @DeleteMapping("/roles/{roleId}")
  public RoleDto archive(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String roleId,
      @RequestParam long expectedVersion,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.archiveRole(
        token(s), HttpSupport.requestId(requestId), HttpSupport.id(roleId), expectedVersion);
  }

  @PutMapping("/roles/{roleId}/permissions")
  public RoleDto replacePermissions(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String roleId,
      @Valid @RequestBody ReplacePermissions body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return authorization.replacePermissions(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(roleId),
        body.expectedVersion(),
        body.permissionKeys(),
        body.reason());
  }

  @PostMapping("/memberships/{membershipId}/roles/{roleId}")
  public ResponseEntity<Void> assignRole(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String membershipId,
      @PathVariable String roleId,
      @Valid @RequestBody Reason body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    authorization.assignRole(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(membershipId),
        HttpSupport.id(roleId),
        body.reason());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/memberships/{membershipId}/roles/{roleId}")
  public ResponseEntity<Void> removeRole(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String membershipId,
      @PathVariable String roleId,
      @Valid @RequestBody Reason body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    authorization.removeRole(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(membershipId),
        HttpSupport.id(roleId),
        body.reason());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/memberships/{membershipId}/overrides/{permissionKey}")
  public ResponseEntity<Void> setOverride(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String membershipId,
      @PathVariable String permissionKey,
      @Valid @RequestBody Override body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    authorization.setOverride(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(membershipId),
        permissionKey,
        body.decision(),
        body.reason());
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/memberships/{membershipId}/overrides/{permissionKey}")
  public ResponseEntity<Void> removeOverride(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String membershipId,
      @PathVariable String permissionKey,
      @Valid @RequestBody Reason body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    authorization.removeOverride(
        token(s),
        HttpSupport.requestId(requestId),
        HttpSupport.id(membershipId),
        permissionKey,
        body.reason());
    return ResponseEntity.noContent().build();
  }

  private String token(BrowserSession s) {
    return identity.issueAudienceToken(
        UUID.randomUUID(), s.refreshCredential(), "authorization-service");
  }

  public record CreateRole(
      @NotBlank @Size(max = 80) String name,
      @Size(max = 500) String description,
      @NotNull @Size(max = 200) List<@NotBlank @Size(max = 128) String> permissionKeys) {
    public CreateRole {
      permissionKeys = permissionKeys == null ? null : List.copyOf(permissionKeys);
    }
  }

  public record UpdateRole(
      @Min(1) long expectedVersion,
      @NotBlank @Size(max = 80) String name,
      @Size(max = 500) String description) {}

  public record ReplacePermissions(
      @Min(1) long expectedVersion,
      @NotNull @Size(max = 200) List<@NotBlank @Size(max = 128) String> permissionKeys,
      @NotBlank @Size(max = 500) String reason) {
    public ReplacePermissions {
      permissionKeys = permissionKeys == null ? null : List.copyOf(permissionKeys);
    }
  }

  public record Reason(@NotBlank @Size(max = 500) String reason) {}

  public record Override(
      @NotBlank @Pattern(regexp = "GRANT|DENY") String decision,
      @NotBlank @Size(max = 500) String reason) {}
}
