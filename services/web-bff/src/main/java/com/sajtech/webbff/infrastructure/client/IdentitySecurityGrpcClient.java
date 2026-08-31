package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.ByteString;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.VerifiedGoogleIdentity;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import com.sajtech.webbff.application.port.out.IdentityGateway.MfaProof;
import io.grpc.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class IdentitySecurityGrpcClient extends IdentityGrpcClientSupport {
  IdentitySecurityGrpcClient(ManagedChannel channel) {
    super(channel);
  }

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

  public ErasureRequest requestSelfErasure(
      UUID requestId, String refresh, String confirmation, MfaProof proof) {
    try {
      var request =
          RequestSelfErasureRequest.newBuilder()
              .setRequestId(requestId.toString())
              .setRefreshCredential(refresh)
              .setConfirmation(confirmation);
      if (proof != null) request.setMfaProof(mfaProof(proof));
      var response = erasureStub().requestSelfErasure(request.build());
      return new ErasureRequest(
          uuid(response.getErasureRequestId()),
          response.getState().name().replace("ERASURE_REQUEST_STATE_", ""),
          response.getParticipantPolicyVersion());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
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
}
