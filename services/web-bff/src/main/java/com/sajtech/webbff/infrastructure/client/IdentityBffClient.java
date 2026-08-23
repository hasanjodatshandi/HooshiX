package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import io.grpc.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class IdentityBffClient implements IdentityGateway {
  private final ManagedChannel channel;

  public IdentityBffClient(ManagedChannel channel) {
    this.channel = Objects.requireNonNull(channel);
  }

  @Override
  public RegisterResult register(
      UUID requestId,
      String channelName,
      String contact,
      String password,
      String localeName,
      String firstName,
      String lastName,
      String fatherName,
      byte[] clientAddress) {
    try {
      RegisterLocalRequest.Builder request =
          RegisterLocalRequest.newBuilder()
              .setRequestId(requestId.toString())
              .setChannel(registrationChannel(channelName))
              .setContact(contact)
              .setPassword(password)
              .setLocale(registrationLocale(localeName))
              .setFirstName(firstName)
              .setLastName(lastName)
              .setClientAddress(
                  TrustedClientAddress.newBuilder()
                      .setAddress(ByteString.copyFrom(clientAddress))
                      .build());
      if (fatherName != null && !fatherName.isBlank()) request.setFatherName(fatherName);
      var response = registrationStub().registerLocal(request.build());
      return new RegisterResult(response.getAccepted());
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
  }

  @Override
  public boolean resendRegistration(
      UUID requestId, String channelName, String contact, byte[] clientAddress) {
    try {
      return registrationStub()
          .resendRegistrationVerification(
              ResendRegistrationVerificationRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(registrationChannel(channelName))
                  .setContact(contact)
                  .setClientAddress(
                      TrustedClientAddress.newBuilder()
                          .setAddress(ByteString.copyFrom(clientAddress))
                          .build())
                  .build())
          .getAccepted();
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
  }

  @Override
  public boolean confirmRegistration(
      UUID requestId, String channelName, String contact, String code, byte[] clientAddress) {
    try {
      return registrationStub()
          .confirmRegistration(
              ConfirmRegistrationRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(registrationChannel(channelName))
                  .setContact(contact)
                  .setCode(code)
                  .setClientAddress(
                      TrustedClientAddress.newBuilder()
                          .setAddress(ByteString.copyFrom(clientAddress))
                          .build())
                  .build())
          .getConfirmed();
    } catch (StatusRuntimeException e) {
      throw mapRegistration(e);
    }
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
      return new LoginResult(
          uuid(r.getUserId()),
          r.getIdentitySessionId(),
          uuid(r.getRefreshFamilyId()),
          r.getRefreshCredential(),
          instant(r.getRefreshIdleExpiresAt()),
          instant(r.getRefreshAbsoluteExpiresAt()),
          mode(r.getSessionMode()),
          optionalUuid(r.getSelectedTenantId()),
          optionalUuid(r.getSelectedMembershipId()));
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

  private IdentityRegistrationServiceGrpc.IdentityRegistrationServiceBlockingStub
      registrationStub() {
    return IdentityRegistrationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static com.sajtech.identity.contract.v1.RegistrationChannel registrationChannel(
      String value) {
    return switch (value) {
      case "EMAIL" ->
          com.sajtech.identity.contract.v1.RegistrationChannel.REGISTRATION_CHANNEL_EMAIL;
      case "PHONE" ->
          com.sajtech.identity.contract.v1.RegistrationChannel.REGISTRATION_CHANNEL_PHONE;
      default ->
          throw new BffException(BffError.INVALID_REQUEST, "Registration channel is invalid");
    };
  }

  private static com.sajtech.identity.contract.v1.RegistrationLocale registrationLocale(
      String value) {
    return switch (value) {
      case "fa" -> com.sajtech.identity.contract.v1.RegistrationLocale.REGISTRATION_LOCALE_FA;
      case "en" -> com.sajtech.identity.contract.v1.RegistrationLocale.REGISTRATION_LOCALE_EN;
      default -> throw new BffException(BffError.INVALID_REQUEST, "Registration locale is invalid");
    };
  }

  private IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceBlockingStub stub() {
    return IdentityAuthenticationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  public Profile profile(String refresh) {
    var r =
        profileStub()
            .getProfile(GetProfileRequest.newBuilder().setRefreshCredential(refresh).build());
    return new Profile(
        uuid(r.getProfileId()), r.getFirstName(), r.getLastName(), r.getFatherName());
  }

  public List<Contact> contacts(String refresh) {
    return profileStub()
        .listContacts(ListContactsRequest.newBuilder().setRefreshCredential(refresh).build())
        .getContactsList()
        .stream()
        .map(
            c ->
                new Contact(
                    uuid(c.getContactId()),
                    c.getType(),
                    c.getValue(),
                    c.getVerified(),
                    c.getPrimary()))
        .toList();
  }

  public UUID addContact(String refresh, String type, String value) {
    return uuid(
        profileStub()
            .addContact(
                AddContactRequest.newBuilder()
                    .setRefreshCredential(refresh)
                    .setType(type)
                    .setValue(value)
                    .build())
            .getContactId());
  }

  public boolean verifyContact(String refresh, UUID id, String code) {
    return profileStub()
        .verifyContact(
            VerifyContactRequest.newBuilder()
                .setRefreshCredential(refresh)
                .setContactId(id.toString())
                .setCode(code)
                .build())
        .getVerified();
  }

  public boolean setPrimaryContact(String refresh, UUID id) {
    return profileStub()
        .setPrimaryContact(
            SetPrimaryContactRequest.newBuilder()
                .setRefreshCredential(refresh)
                .setContactId(id.toString())
                .build())
        .getAccepted();
  }

  public boolean removeContact(String refresh, UUID id) {
    return profileStub()
        .removeContact(
            RemoveContactRequest.newBuilder()
                .setRefreshCredential(refresh)
                .setContactId(id.toString())
                .build())
        .getAccepted();
  }

  private IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceBlockingStub tokenStub() {
    return IdentityAuthenticationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1000, TimeUnit.MILLISECONDS);
  }

  private IdentityProfileServiceGrpc.IdentityProfileServiceBlockingStub profileStub() {
    return IdentityProfileServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private IdentityTenantServiceGrpc.IdentityTenantServiceBlockingStub tenantStub() {
    return IdentityTenantServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static BffException mapRegistration(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case RESOURCE_EXHAUSTED ->
          "QUOTA_EXCEEDED".equals(e.getStatus().getDescription())
              ? new BffException(BffError.RATE_LIMITED, "Registration request quota exceeded", e)
              : new BffException(
                  BffError.DEPENDENCY_UNAVAILABLE, "Identity registration is unavailable", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "Registration request is invalid", e);
      case ALREADY_EXISTS, FAILED_PRECONDITION ->
          new BffException(BffError.REGISTRATION_REJECTED, "Registration request was rejected", e);
      default ->
          new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity registration is unavailable", e);
    };
  }

  private static BffException map(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(BffError.AUTHENTICATION_FAILED, "Authentication failed", e);
      case PERMISSION_DENIED ->
          new BffException(BffError.AUTHORIZATION_DENIED, "Authorization denied", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "Request quota exceeded", e);
      case FAILED_PRECONDITION ->
          new BffException(
              "TENANT_SELECTION_REQUIRED".equals(e.getStatus().getDescription())
                  ? BffError.TENANT_SELECTION_REQUIRED
                  : BffError.INVALID_REQUEST,
              "Request precondition failed",
              e);
      case INVALID_ARGUMENT, ALREADY_EXISTS, NOT_FOUND ->
          new BffException(BffError.INVALID_REQUEST, "Request is invalid", e);
      default -> new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity is unavailable", e);
    };
  }

  private static UUID uuid(String v) {
    try {
      return UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid UUID", e);
    }
  }

  private static UUID optionalUuid(String v) {
    return v == null || v.isBlank() ? null : uuid(v);
  }

  private static Instant instant(Timestamp t) {
    try {
      return Instant.ofEpochSecond(t.getSeconds(), t.getNanos());
    } catch (RuntimeException e) {
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Identity returned invalid timestamp", e);
    }
  }

  private static SessionMode mode(AuthenticationSessionMode mode) {
    return switch (mode) {
      case AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING ->
          SessionMode.AUTHENTICATED_ONBOARDING;
      case AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED -> SessionMode.TENANT_AUTHENTICATED;
      default ->
          throw new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity returned unexpected session mode");
    };
  }
}
