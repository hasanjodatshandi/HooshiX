package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.ByteString;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import io.grpc.*;
import java.util.*;

class IdentityTenantGrpcClient extends IdentityGrpcClientSupport {
  IdentityTenantGrpcClient(ManagedChannel channel) {
    super(channel);
  }

  public LoginResult login(
      UUID requestId, String channelName, String contact, String password, byte[] clientAddress) {
    try {
      AuthenticationChannel type =
          switch (channelName) {
            case "EMAIL" -> AuthenticationChannel.AUTHENTICATION_CHANNEL_EMAIL;
            case "PHONE" -> AuthenticationChannel.AUTHENTICATION_CHANNEL_PHONE;
            default ->
                throw new BffException(
                    BffError.INVALID_REQUEST, "Authentication channel is invalid");
          };
      var r =
          stub()
              .authenticateLocal(
                  AuthenticateLocalRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setChannel(type)
                      .setContact(contact)
                      .setPassword(password)
                      .setClientAddress(
                          AuthenticationTrustedClientAddress.newBuilder()
                              .setAddress(ByteString.copyFrom(clientAddress)))
                      .build());
      SessionMode sessionMode = mode(r.getSessionMode());
      if (sessionMode == SessionMode.MFA_REQUIRED) {
        if (!r.getMfaChallenge().matches("[A-Za-z0-9_-]{43}")) {
          throw new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid MFA challenge");
        }
        return new LoginResult(
            uuid(r.getUserId()),
            null,
            null,
            null,
            null,
            null,
            sessionMode,
            null,
            null,
            r.getMfaChallenge());
      }
      return new LoginResult(
          uuid(r.getUserId()),
          r.getIdentitySessionId(),
          uuid(r.getRefreshFamilyId()),
          r.getRefreshCredential(),
          instant(r.getRefreshIdleExpiresAt()),
          instant(r.getRefreshAbsoluteExpiresAt()),
          sessionMode,
          optionalUuid(r.getSelectedTenantId()),
          optionalUuid(r.getSelectedMembershipId()),
          null);
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public ListResult listTenants(String refresh) {
    try {
      var r =
          tenantStub()
              .listSelectableTenants(
                  ListSelectableTenantsRequest.newBuilder().setRefreshCredential(refresh).build());
      List<TenantChoice> choices = new ArrayList<>();
      for (var t : r.getTenantsList())
        choices.add(
            new TenantChoice(
                uuid(t.getTenantId()), uuid(t.getMembershipId()), t.getName(), t.getSlug()));
      UUID suggested =
          r.getSuggestedMembershipId().isBlank() ? null : uuid(r.getSuggestedMembershipId());
      return new ListResult(List.copyOf(choices), suggested);
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public SelectResult selectTenant(
      UUID requestId, String refresh, UUID membershipId, String audience) {
    try {
      var r =
          tenantStub()
              .selectTenant(
                  SelectTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setMembershipId(membershipId.toString())
                      .setAudience(audience)
                      .build());
      return new SelectResult(
          r.getIdentitySessionId(),
          uuid(r.getRefreshFamilyId()),
          r.getRefreshCredential(),
          instant(r.getRefreshIdleExpiresAt()),
          instant(r.getRefreshAbsoluteExpiresAt()),
          uuid(r.getTenantId()),
          uuid(r.getMembershipId()));
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public TenantCreated createTenant(UUID requestId, String refresh, String name, String slug) {
    try {
      var r =
          tenantStub()
              .createTenant(
                  CreateTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setName(name)
                      .setSlug(slug)
                      .build());
      return new TenantCreated(
          uuid(r.getTenantId()), uuid(r.getCreatorMembershipId()), r.getLifecycle());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public InvitationCreated invite(UUID requestId, String refresh, UUID contactId) {
    try {
      var r =
          tenantStub()
              .inviteExistingUser(
                  InviteExistingUserRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setTargetContactId(contactId.toString())
                      .build());
      return new InvitationCreated(uuid(r.getInvitationId()), instant(r.getExpiresAt()));
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public AcceptedInvitation accept(UUID requestId, String refresh, UUID invitation) {
    try {
      var r =
          tenantStub()
              .acceptInvitation(
                  AcceptInvitationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setInvitationId(invitation.toString())
                      .build());
      return new AcceptedInvitation(uuid(r.getTenantId()), uuid(r.getMembershipId()));
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public void removeMembership(UUID requestId, String refresh, UUID membership) {
    try {
      tenantStub()
          .removeMembership(
              RemoveMembershipRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setRefreshCredential(refresh)
                  .setMembershipId(membership.toString())
                  .build());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public TenantLifecycleResult suspendTenant(UUID requestId, String refresh, UUID tenantId) {
    try {
      var r =
          tenantStub()
              .suspendTenant(
                  SuspendTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setTenantId(tenantId.toString())
                      .build());
      return tenantLifecycle(
          r.getTenantId(), r.getLifecycle(), r.getTargetLifecycle(), r.getPending());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public TenantLifecycleResult resumeTenant(UUID requestId, String refresh, UUID tenantId) {
    try {
      var r =
          tenantStub()
              .resumeTenant(
                  ResumeTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setTenantId(tenantId.toString())
                      .build());
      return tenantLifecycle(
          r.getTenantId(), r.getLifecycle(), r.getTargetLifecycle(), r.getPending());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public TenantLifecycleResult deleteTenant(UUID requestId, String refresh, UUID tenantId) {
    try {
      var r =
          tenantStub()
              .deleteTenant(
                  DeleteTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setTenantId(tenantId.toString())
                      .build());
      return tenantLifecycle(
          r.getTenantId(), r.getLifecycle(), r.getTargetLifecycle(), r.getPending());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public TenantLifecycleResult restoreTenant(UUID requestId, String refresh, UUID tenantId) {
    try {
      var r =
          tenantStub()
              .restoreTenant(
                  RestoreTenantRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setTenantId(tenantId.toString())
                      .build());
      return tenantLifecycle(
          r.getTenantId(), r.getLifecycle(), r.getTargetLifecycle(), r.getPending());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  private static TenantLifecycleResult tenantLifecycle(
      String tenantId, String lifecycle, String targetLifecycle, boolean pending) {
    return new TenantLifecycleResult(uuid(tenantId), lifecycle, targetLifecycle, pending);
  }

  public List<Invitation> receivedInvitations(String refresh) {
    try {
      return invitations(
          tenantStub()
              .listReceivedInvitations(
                  ListReceivedInvitationsRequest.newBuilder().setRefreshCredential(refresh).build())
              .getInvitationsList());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public List<Invitation> tenantInvitations(String refresh) {
    try {
      return invitations(
          tenantStub()
              .listTenantInvitations(
                  ListTenantInvitationsRequest.newBuilder().setRefreshCredential(refresh).build())
              .getInvitationsList());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  private static List<Invitation> invitations(
      List<com.sajtech.identity.contract.v1.InvitationSummary> response) {
    List<Invitation> result = new ArrayList<>();
    for (var r : response)
      result.add(
          new Invitation(
              uuid(r.getInvitationId()),
              uuid(r.getTenantId()),
              r.getTenantName(),
              r.getTenantSlug(),
              r.getState(),
              instant(r.getExpiresAt())));
    return List.copyOf(result);
  }

  public InvitationState declineInvitation(UUID requestId, String refresh, UUID invitationId) {
    try {
      var r =
          tenantStub()
              .declineInvitation(
                  DeclineInvitationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setInvitationId(invitationId.toString())
                      .build());
      return new InvitationState(uuid(r.getInvitationId()), r.getState());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public InvitationState revokeInvitation(UUID requestId, String refresh, UUID invitationId) {
    try {
      var r =
          tenantStub()
              .revokeInvitation(
                  RevokeInvitationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setInvitationId(invitationId.toString())
                      .build());
      return new InvitationState(uuid(r.getInvitationId()), r.getState());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public InvitationCreated reissueInvitation(UUID requestId, String refresh, UUID invitationId) {
    try {
      var r =
          tenantStub()
              .reissueInvitation(
                  ReissueInvitationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setInvitationId(invitationId.toString())
                      .build());
      return new InvitationCreated(uuid(r.getInvitationId()), instant(r.getExpiresAt()));
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public String issueAudienceToken(UUID requestId, String refresh, String audience) {
    try {
      return tokenStub()
          .issueAudienceAccessToken(
              IssueAudienceAccessTokenRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setRefreshCredential(refresh)
                  .setAudience(audience)
                  .build())
          .getAccessToken();
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }

  public void logout(UUID requestId, String refresh) {
    try {
      stub()
          .logoutCurrent(
              LogoutCurrentRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setRefreshCredential(refresh)
                  .build());
    } catch (StatusRuntimeException e) {
      throw map(e);
    }
  }
}
