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
import java.util.function.Supplier;

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

  @Override
  public PasswordChangeResult changePassword(
      UUID requestId, String refresh, String currentPassword, String newPassword) {
    try {
      var response =
          passwordStub()
              .changePassword(
                  ChangePasswordRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setCurrentPassword(currentPassword)
                      .setNewPassword(newPassword)
                      .build());
      return new PasswordChangeResult(
          response.getRefreshCredential(),
          instant(response.getRefreshIdleExpiresAt()),
          instant(response.getRefreshAbsoluteExpiresAt()));
    } catch (StatusRuntimeException e) {
      throw mapPassword(e);
    }
  }

  @Override
  public boolean requestPasswordRecovery(
      UUID requestId, String channelName, String contact, byte[] clientAddress) {
    try {
      return passwordStub()
          .requestPasswordRecovery(
              RequestPasswordRecoveryRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(authenticationChannel(channelName))
                  .setPrimaryContact(contact)
                  .setClientAddress(passwordClientAddress(clientAddress))
                  .build())
          .getAccepted();
    } catch (StatusRuntimeException e) {
      throw mapPassword(e);
    }
  }

  @Override
  public boolean confirmPasswordRecovery(
      UUID requestId,
      String channelName,
      String contact,
      String code,
      String newPassword,
      byte[] clientAddress) {
    try {
      return passwordStub()
          .confirmPasswordRecovery(
              ConfirmPasswordRecoveryRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setChannel(authenticationChannel(channelName))
                  .setPrimaryContact(contact)
                  .setCode(code)
                  .setNewPassword(newPassword)
                  .setClientAddress(passwordClientAddress(clientAddress))
                  .build())
          .getChanged();
    } catch (StatusRuntimeException e) {
      throw mapPassword(e);
    }
  }

  private com.sajtech.identity.contract.v1.IdentityPasswordServiceGrpc
          .IdentityPasswordServiceBlockingStub
      passwordStub() {
    return com.sajtech.identity.contract.v1.IdentityPasswordServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static AuthenticationChannel authenticationChannel(String value) {
    return switch (value) {
      case "EMAIL" -> AuthenticationChannel.AUTHENTICATION_CHANNEL_EMAIL;
      case "PHONE" -> AuthenticationChannel.AUTHENTICATION_CHANNEL_PHONE;
      default -> throw new BffException(BffError.INVALID_REQUEST, "Password channel is invalid");
    };
  }

  private static PasswordTrustedClientAddress passwordClientAddress(byte[] value) {
    return PasswordTrustedClientAddress.newBuilder().setAddress(ByteString.copyFrom(value)).build();
  }

  private IdentityAuthenticationServiceGrpc.IdentityAuthenticationServiceBlockingStub stub() {
    return IdentityAuthenticationServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  public Profile profile(String refresh) {
    return profileCall(
        () -> {
          var r =
              profileStub()
                  .getProfile(GetProfileRequest.newBuilder().setRefreshCredential(refresh).build());
          return new Profile(
              uuid(r.getProfileId()), r.getFirstName(), r.getLastName(), r.getFatherName());
        });
  }

  public List<Contact> contacts(String refresh) {
    return profileCall(
        () ->
            profileStub()
                .listContacts(
                    ListContactsRequest.newBuilder().setRefreshCredential(refresh).build())
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
                .toList());
  }

  @Override
  public boolean updateProfile(
      UUID requestId, String refresh, String firstName, String lastName, String fatherName) {
    return profileCall(
        () ->
            profileStub()
                .updateProfile(
                    UpdateProfileRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setRequestId(requestId.toString())
                        .setFirstName(firstName)
                        .setLastName(lastName)
                        .setFatherName(fatherName == null ? "" : fatherName)
                        .build())
                .getUpdated());
  }

  public UUID addContact(UUID requestId, String refresh, String type, String value, String locale) {
    return profileCall(
        () ->
            uuid(
                profileStub()
                    .addContact(
                        AddContactRequest.newBuilder()
                            .setRefreshCredential(refresh)
                            .setType(type)
                            .setValue(value)
                            .setRequestId(requestId.toString())
                            .setLocale(locale)
                            .build())
                    .getContactId()));
  }

  public boolean resendContactVerification(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .resendContactVerification(
                    ResendContactVerificationRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  public boolean verifyContact(UUID requestId, String refresh, UUID id, String code) {
    return profileCall(
        () ->
            profileStub()
                .verifyContact(
                    VerifyContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setCode(code)
                        .setRequestId(requestId.toString())
                        .build())
                .getVerified());
  }

  public boolean setPrimaryContact(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .setPrimaryContact(
                    SetPrimaryContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  public boolean removeContact(UUID requestId, String refresh, UUID id) {
    return profileCall(
        () ->
            profileStub()
                .removeContact(
                    RemoveContactRequest.newBuilder()
                        .setRefreshCredential(refresh)
                        .setContactId(id.toString())
                        .setRequestId(requestId.toString())
                        .build())
                .getAccepted());
  }

  private static <T> T profileCall(Supplier<T> call) {
    try {
      return call.get();
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
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

  private static BffException mapPassword(StatusRuntimeException e) {
    String description = e.getStatus().getDescription();
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          "INVALID_SESSION".equals(description)
              ? new BffException(BffError.AUTHENTICATION_FAILED, "Password session is invalid", e)
              : new BffException(BffError.PASSWORD_REJECTED, "Password proof was rejected", e);
      case PERMISSION_DENIED, FAILED_PRECONDITION ->
          new BffException(BffError.PASSWORD_REJECTED, "Password request was rejected", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "Password request quota exceeded", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "Password request is invalid", e);
      default ->
          new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Password service is unavailable", e);
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
      UUID result = UUID.fromString(v);
      if (result.version() != 4 || !result.toString().equals(v)) {
        throw new IllegalArgumentException("UUID is not canonical UUIDv4");
      }
      return result;
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
