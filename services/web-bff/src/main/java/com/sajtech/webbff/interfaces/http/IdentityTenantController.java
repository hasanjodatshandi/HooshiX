package com.sajtech.webbff.interfaces.http;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Clock;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!migration")
@RequestMapping("/api/v1/identity")
public final class IdentityTenantController {
  private final IdentityGateway identity;
  private final BrowserSessionPort sessions;
  private final Clock clock;

  public IdentityTenantController(
      IdentityGateway identity, BrowserSessionPort sessions, Clock clock) {
    this.identity = identity;
    this.sessions = sessions;
    this.clock = clock;
  }

  @GetMapping("/tenants")
  public TenantList tenants(HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    var r = identity.listTenants(s.refreshCredential());
    return new TenantList(
        r.tenants().stream()
            .map(x -> new TenantChoice(x.tenantId(), x.membershipId(), x.name(), x.slug()))
            .toList(),
        r.suggestedMembershipId());
  }

  @PostMapping("/tenants")
  public TenantCreated create(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody CreateTenant body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    var r =
        identity.createTenant(
            HttpSupport.requestId(requestId), s.refreshCredential(), body.name(), body.slug());
    return new TenantCreated(r.tenantId(), r.membershipId(), r.lifecycle());
  }

  @PostMapping("/tenant-selection")
  public TenantSelectionResponse select(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody SelectTenant body,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.authenticated(request);
    var r =
        identity.selectTenant(
            HttpSupport.requestId(requestId),
            old.refreshCredential(),
            HttpSupport.id(body.membershipId()),
            "authorization-service");
    if (!old.identitySessionId().equals(r.identitySessionId())
        || !old.refreshFamilyId().equals(r.refreshFamilyId()))
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Identity session continuity is invalid");
    BrowserSessionGrant grant =
        sessions.rotateTenant(
            old,
            r.refreshCredential(),
            r.idleExpiresAt(),
            r.absoluteExpiresAt(),
            r.tenantId(),
            r.membershipId());
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return new TenantSelectionResponse(
        grant.csrfToken(), r.tenantId(), r.membershipId(), grant.session().mode().name());
  }

  @PostMapping("/invitations")
  public InvitationCreated invite(
      @RequestHeader("X-Request-Id") String requestId,
      @Valid @RequestBody Invite body,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    var r =
        identity.invite(
            HttpSupport.requestId(requestId),
            s.refreshCredential(),
            HttpSupport.id(body.targetContactId()));
    return new InvitationCreated(r.invitationId(), r.expiresAt().toString());
  }

  @PostMapping("/invitations/{invitationId}/accept")
  public AcceptedInvitation accept(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String invitationId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    var r =
        identity.accept(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(invitationId));
    return new AcceptedInvitation(r.tenantId(), r.membershipId());
  }

  @DeleteMapping("/memberships/{membershipId}")
  public RemovalResult remove(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String membershipId,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.tenant(request);
    UUID target = HttpSupport.id(membershipId);
    identity.removeMembership(HttpSupport.requestId(requestId), old.refreshCredential(), target);
    if (target.equals(old.selectedMembershipId())) {
      BrowserSessionGrant grant =
          sessions.rotateAuthenticated(
              old,
              old.userId(),
              old.identitySessionId(),
              old.refreshFamilyId(),
              old.refreshCredential(),
              old.idleExpiresAt(),
              old.absoluteExpiresAt());
      HttpSupport.setCookie(
          response,
          grant.cookieValue(),
          HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
      return new RemovalResult(true, grant.csrfToken(), grant.session().mode().name());
    }
    return new RemovalResult(true, null, old.mode().name());
  }

  @PostMapping("/tenants/{tenantId}/suspend")
  public LifecycleResult suspend(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String tenantId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return lifecycle(
        identity.suspendTenant(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(tenantId)));
  }

  @PostMapping("/tenants/{tenantId}/resume")
  public LifecycleResult resume(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String tenantId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return lifecycle(
        identity.resumeTenant(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(tenantId)));
  }

  @DeleteMapping("/tenants/{tenantId}")
  public LifecycleResult delete(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String tenantId,
      HttpServletRequest request,
      HttpServletResponse response) {
    BrowserSession old = HttpSupport.tenant(request);
    UUID target = HttpSupport.id(tenantId);
    if (!target.equals(old.selectedTenantId()))
      throw new BffException(BffError.AUTHORIZATION_DENIED, "Selected tenant does not match");
    var result =
        lifecycle(
            identity.deleteTenant(
                HttpSupport.requestId(requestId), old.refreshCredential(), target));
    BrowserSessionGrant grant =
        sessions.rotateAuthenticated(
            old,
            old.userId(),
            old.identitySessionId(),
            old.refreshFamilyId(),
            old.refreshCredential(),
            old.idleExpiresAt(),
            old.absoluteExpiresAt());
    HttpSupport.setCookie(
        response,
        grant.cookieValue(),
        HttpSupport.maxAge(clock.instant(), grant.session().idleExpiresAt()));
    return new LifecycleResult(
        result.tenantId(),
        result.lifecycle(),
        result.targetLifecycle(),
        result.pending(),
        grant.csrfToken(),
        grant.session().mode().name());
  }

  @PostMapping("/tenants/{tenantId}/restore")
  public LifecycleResult restore(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String tenantId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return lifecycle(
        identity.restoreTenant(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(tenantId)));
  }

  @GetMapping("/invitations/received")
  public InvitationList receivedInvitations(HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    return invitations(identity.receivedInvitations(s.refreshCredential()));
  }

  @GetMapping("/invitations")
  public InvitationList tenantInvitations(HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    return invitations(identity.tenantInvitations(s.refreshCredential()));
  }

  @PostMapping("/invitations/{invitationId}/decline")
  public InvitationState decline(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String invitationId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.authenticated(request);
    var r =
        identity.declineInvitation(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(invitationId));
    return new InvitationState(r.invitationId(), r.state());
  }

  @PostMapping("/invitations/{invitationId}/revoke")
  public InvitationState revoke(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String invitationId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    var r =
        identity.revokeInvitation(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(invitationId));
    return new InvitationState(r.invitationId(), r.state());
  }

  @PostMapping("/invitations/{invitationId}/reissue")
  public InvitationCreated reissue(
      @RequestHeader("X-Request-Id") String requestId,
      @PathVariable String invitationId,
      HttpServletRequest request) {
    BrowserSession s = HttpSupport.tenant(request);
    var r =
        identity.reissueInvitation(
            HttpSupport.requestId(requestId), s.refreshCredential(), HttpSupport.id(invitationId));
    return new InvitationCreated(r.invitationId(), r.expiresAt().toString());
  }

  private static LifecycleResult lifecycle(IdentityGateway.TenantLifecycleResult r) {
    return new LifecycleResult(
        r.tenantId(), r.lifecycle(), r.targetLifecycle(), r.pending(), null, null);
  }

  private static InvitationList invitations(List<IdentityGateway.Invitation> values) {
    return new InvitationList(
        values.stream()
            .map(
                x ->
                    new InvitationSummary(
                        x.invitationId(),
                        x.tenantId(),
                        x.tenantName(),
                        x.tenantSlug(),
                        x.state(),
                        x.expiresAt().toString()))
            .toList());
  }

  public record CreateTenant(
      @NotBlank @Size(max = 120) String name, @NotBlank @Size(min = 3, max = 63) String slug) {}

  public record SelectTenant(@NotBlank String membershipId) {}

  public record Invite(@NotBlank String targetContactId) {}

  public record TenantChoice(UUID tenantId, UUID membershipId, String name, String slug) {}

  public record TenantList(List<TenantChoice> tenants, UUID suggestedMembershipId) {
    public TenantList {
      tenants = List.copyOf(tenants);
    }
  }

  public record TenantCreated(UUID tenantId, UUID membershipId, String lifecycle) {}

  public record TenantSelectionResponse(
      String csrfToken, UUID tenantId, UUID membershipId, String mode) {}

  public record InvitationCreated(UUID invitationId, String expiresAt) {}

  public record AcceptedInvitation(UUID tenantId, UUID membershipId) {}

  public record RemovalResult(boolean accepted, String csrfToken, String mode) {}

  public record LifecycleResult(
      UUID tenantId,
      String lifecycle,
      String targetLifecycle,
      boolean pending,
      String csrfToken,
      String mode) {}

  public record InvitationSummary(
      UUID invitationId,
      UUID tenantId,
      String tenantName,
      String tenantSlug,
      String state,
      String expiresAt) {}

  public record InvitationList(List<InvitationSummary> invitations) {
    public InvitationList {
      invitations = List.copyOf(invitations);
    }
  }

  public record InvitationState(UUID invitationId, String state) {}
}
