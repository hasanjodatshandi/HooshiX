package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.VerifiedGoogleIdentity;
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
      byte[] clientAddress,
      MfaProof mfaProof) {
    try {
      ConfirmPasswordRecoveryRequest.Builder request =
          ConfirmPasswordRecoveryRequest.newBuilder()
              .setRequestId(requestId.toString())
              .setChannel(authenticationChannel(channelName))
              .setPrimaryContact(contact)
              .setCode(code)
              .setNewPassword(newPassword)
              .setClientAddress(passwordClientAddress(clientAddress));
      if (mfaProof != null) request.setMfaProof(mfaProof(mfaProof));
      return passwordStub().confirmPasswordRecovery(request.build()).getChanged();
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

  @Override
  public MfaStatus mfaStatus(UUID requestId, String refresh) {
    try {
      var response =
          mfaStub()
              .getMfaStatus(
                  GetMfaStatusRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .build());
      return new MfaStatus(
          response.getTotpEnabled(), Math.toIntExact(response.getRecoveryCodesRemaining()));
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public TotpEnrollmentStart startTotpEnrollment(
      UUID requestId, String refresh, byte[] clientAddress, MfaProof currentProof) {
    try {
      StartTotpEnrollmentRequest.Builder request =
          StartTotpEnrollmentRequest.newBuilder()
              .setRequestId(requestId.toString())
              .setRefreshCredential(refresh)
              .setClientAddress(authenticationClientAddress(clientAddress));
      if (currentProof != null) request.setCurrentProof(mfaProof(currentProof));
      var response = mfaStub().startTotpEnrollment(request.build());
      return new TotpEnrollmentStart(
          response.getEnrollmentChallenge(),
          response.getBase32Secret(),
          response.getOtpauthUri(),
          instant(response.getExpiresAt()));
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public MfaMutation confirmTotpEnrollment(
      UUID requestId,
      String refresh,
      String enrollmentChallenge,
      String totpCode,
      byte[] clientAddress) {
    try {
      var response =
          mfaStub()
              .confirmTotpEnrollment(
                  ConfirmTotpEnrollmentRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setEnrollmentChallenge(enrollmentChallenge)
                      .setTotpCode(totpCode)
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return mutation(response.getSession(), response.getRecoveryCodesList());
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public MfaMutation disableTotp(
      UUID requestId, String refresh, MfaProof proof, byte[] clientAddress) {
    try {
      var response =
          mfaStub()
              .disableTotp(
                  DisableTotpRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setProof(mfaProof(proof))
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return mutation(response.getSession(), List.of());
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public MfaMutation rotateRecoveryCodes(
      UUID requestId, String refresh, MfaProof proof, byte[] clientAddress) {
    try {
      var response =
          mfaStub()
              .rotateRecoveryCodes(
                  RotateRecoveryCodesRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setProof(mfaProof(proof))
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return mutation(response.getSession(), response.getRecoveryCodesList());
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public LoginResult completeMfaAuthentication(
      UUID requestId, String challenge, MfaProof proof, byte[] clientAddress) {
    try {
      var response =
          mfaStub()
              .completeMfaAuthentication(
                  CompleteMfaAuthenticationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setMfaChallenge(challenge)
                      .setProof(mfaProof(proof))
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return session(response.getSession());
    } catch (StatusRuntimeException exception) {
      throw mapMfa(exception);
    }
  }

  @Override
  public LoginResult establishExternalIdentity(
      UUID requestId,
      byte[] evidenceId,
      Instant evidenceIssuedAt,
      VerifiedGoogleIdentity identity,
      byte[] clientAddress) {
    try {
      var response =
          externalIdentityStub()
              .establishSession(
                  EstablishSessionRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setEvidence(evidence(evidenceId, evidenceIssuedAt, identity))
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return session(response.getAuthentication());
    } catch (StatusRuntimeException exception) {
      throw mapExternalIdentity(exception);
    }
  }

  @Override
  public LoginResult linkExternalIdentity(
      UUID requestId,
      String refresh,
      byte[] evidenceId,
      Instant evidenceIssuedAt,
      VerifiedGoogleIdentity identity,
      byte[] clientAddress) {
    try {
      var response =
          externalIdentityStub()
              .link(
                  LinkRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setEvidence(evidence(evidenceId, evidenceIssuedAt, identity))
                      .setClientAddress(authenticationClientAddress(clientAddress))
                      .build());
      return session(response.getSession());
    } catch (StatusRuntimeException exception) {
      throw mapExternalIdentity(exception);
    }
  }

  @Override
  public LoginResult unlinkExternalIdentity(UUID requestId, String refresh) {
    try {
      return session(
          externalIdentityStub()
              .unlink(
                  UnlinkRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setRefreshCredential(refresh)
                      .setIssuer("https://accounts.google.com")
                      .build())
              .getSession());
    } catch (StatusRuntimeException exception) {
      throw mapExternalIdentity(exception);
    }
  }

  @Override
  public boolean googleIdentityLinked(UUID requestId, String refresh) {
    try {
      return externalIdentityStub()
          .getStatus(
              GetStatusRequest.newBuilder()
                  .setRequestId(requestId.toString())
                  .setRefreshCredential(refresh)
                  .build())
          .getGoogleLinked();
    } catch (StatusRuntimeException exception) {
      throw mapExternalIdentity(exception);
    }
  }

  private static ExternalIdentityEvidence evidence(
      byte[] evidenceId, Instant issuedAt, VerifiedGoogleIdentity identity) {
    ExternalIdentityEvidence.Builder builder =
        ExternalIdentityEvidence.newBuilder()
            .setEvidenceId(ByteString.copyFrom(evidenceId))
            .setEvidenceIssuedAt(timestamp(issuedAt))
            .setIssuer(identity.issuer())
            .setSubject(identity.subject())
            .setMetadataVersion(1)
            .setEmailVerified(identity.emailVerified());
    if (identity.email() != null) builder.setEmail(identity.email());
    if (identity.givenName() != null) builder.setGivenName(identity.givenName());
    if (identity.familyName() != null) builder.setFamilyName(identity.familyName());
    return builder.build();
  }

  private IdentityExternalIdentityServiceGrpc.IdentityExternalIdentityServiceBlockingStub
      externalIdentityStub() {
    return IdentityExternalIdentityServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static LoginResult session(AuthenticateLocalResponse value) {
    SessionMode sessionMode = mode(value.getSessionMode());
    if (sessionMode == SessionMode.MFA_REQUIRED) {
      return new LoginResult(
          uuid(value.getUserId()),
          null,
          null,
          null,
          null,
          null,
          sessionMode,
          null,
          null,
          value.getMfaChallenge());
    }
    return new LoginResult(
        uuid(value.getUserId()),
        value.getIdentitySessionId(),
        uuid(value.getRefreshFamilyId()),
        value.getRefreshCredential(),
        instant(value.getRefreshIdleExpiresAt()),
        instant(value.getRefreshAbsoluteExpiresAt()),
        sessionMode,
        optionalUuid(value.getSelectedTenantId()),
        optionalUuid(value.getSelectedMembershipId()),
        null);
  }

  private static LoginResult session(ExternalIdentitySession value) {
    return new LoginResult(
        uuid(value.getUserId()),
        value.getIdentitySessionId(),
        uuid(value.getRefreshFamilyId()),
        value.getRefreshCredential(),
        instant(value.getRefreshIdleExpiresAt()),
        instant(value.getRefreshAbsoluteExpiresAt()),
        mode(value.getSessionMode()),
        optionalUuid(value.getSelectedTenantId()),
        optionalUuid(value.getSelectedMembershipId()),
        null);
  }

  private IdentityMfaServiceGrpc.IdentityMfaServiceBlockingStub mfaStub() {
    return IdentityMfaServiceGrpc.newBlockingStub(channel)
        .withDeadlineAfter(1500, TimeUnit.MILLISECONDS);
  }

  private static MfaMutation mutation(
      MfaSessionCredentials credentials, List<String> recoveryCodes) {
    return new MfaMutation(session(credentials), recoveryCodes);
  }

  private static LoginResult session(MfaSessionCredentials value) {
    return new LoginResult(
        uuid(value.getUserId()),
        value.getIdentitySessionId(),
        uuid(value.getRefreshFamilyId()),
        value.getRefreshCredential(),
        instant(value.getRefreshIdleExpiresAt()),
        instant(value.getRefreshAbsoluteExpiresAt()),
        mode(value.getSessionMode()),
        optionalUuid(value.getSelectedTenantId()),
        optionalUuid(value.getSelectedMembershipId()),
        null);
  }

  private static com.sajtech.identity.contract.v1.MfaProof mfaProof(MfaProof value) {
    if (value == null) throw new BffException(BffError.INVALID_REQUEST, "MFA proof is required");
    return com.sajtech.identity.contract.v1.MfaProof.newBuilder()
        .setType(
            switch (value.type()) {
              case "TOTP" -> MfaProofType.MFA_PROOF_TYPE_TOTP;
              case "RECOVERY_CODE" -> MfaProofType.MFA_PROOF_TYPE_RECOVERY_CODE;
              default ->
                  throw new BffException(BffError.INVALID_REQUEST, "MFA proof type is invalid");
            })
        .setCode(value.code())
        .build();
  }

  private static AuthenticationTrustedClientAddress authenticationClientAddress(byte[] value) {
    return AuthenticationTrustedClientAddress.newBuilder()
        .setAddress(ByteString.copyFrom(value))
        .build();
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

  private static BffException mapMfa(StatusRuntimeException e) {
    return switch (e.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(BffError.AUTHENTICATION_FAILED, "MFA proof was rejected", e);
      case RESOURCE_EXHAUSTED ->
          new BffException(BffError.RATE_LIMITED, "MFA request quota exceeded", e);
      case FAILED_PRECONDITION, ABORTED ->
          new BffException(BffError.INVALID_REQUEST, "MFA request precondition failed", e);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "MFA request is invalid", e);
      default -> new BffException(BffError.DEPENDENCY_UNAVAILABLE, "MFA is unavailable", e);
    };
  }

  private static BffException mapExternalIdentity(StatusRuntimeException exception) {
    String description = exception.getStatus().getDescription();
    return switch (exception.getStatus().getCode()) {
      case UNAUTHENTICATED ->
          new BffException(
              BffError.OIDC_INVALID_RESPONSE, "External identity proof was rejected", exception);
      case ALREADY_EXISTS ->
          new BffException(
              BffError.EXTERNAL_IDENTITY_REJECTED,
              "External identity replay or conflict",
              exception);
      case FAILED_PRECONDITION ->
          new BffException(
              "ACCOUNT_LINK_REQUIRED".equals(description)
                  ? BffError.ACCOUNT_LINK_REQUIRED
                  : BffError.EXTERNAL_IDENTITY_REJECTED,
              "External identity precondition failed",
              exception);
      case INVALID_ARGUMENT ->
          new BffException(
              BffError.INVALID_REQUEST, "External identity request is invalid", exception);
      default ->
          new BffException(
              BffError.DEPENDENCY_UNAVAILABLE,
              "External identity service is unavailable",
              exception);
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

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static SessionMode mode(AuthenticationSessionMode mode) {
    return switch (mode) {
      case AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING ->
          SessionMode.AUTHENTICATED_ONBOARDING;
      case AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED -> SessionMode.TENANT_AUTHENTICATED;
      case AUTHENTICATION_SESSION_MODE_MFA_REQUIRED -> SessionMode.MFA_REQUIRED;
      default ->
          throw new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Identity returned unexpected session mode");
    };
  }
}
