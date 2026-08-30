package com.sajtech.webbff.infrastructure.client;

import com.sajtech.webbff.application.model.VerifiedGoogleIdentity;
import com.sajtech.webbff.application.port.out.IdentityGateway;
import com.sajtech.webbff.application.port.out.IdentityGateway.*;
import io.grpc.ManagedChannel;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class IdentityBffClient extends IdentityTenantGrpcClient implements IdentityGateway {
  private final IdentityRegistrationGrpcClient registration;
  private final IdentitySecurityGrpcClient security;
  private final IdentityProfileGrpcClient profile;

  public IdentityBffClient(ManagedChannel channel) {
    super(channel);
    Objects.requireNonNull(channel);
    registration = new IdentityRegistrationGrpcClient(channel);
    security = new IdentitySecurityGrpcClient(channel);
    profile = new IdentityProfileGrpcClient(channel);
  }

  @Override
  public RegisterResult register(
      UUID requestId,
      String channel,
      String contact,
      String password,
      String locale,
      String firstName,
      String lastName,
      String fatherName,
      byte[] clientAddress) {
    return registration.register(
        requestId,
        channel,
        contact,
        password,
        locale,
        firstName,
        lastName,
        fatherName,
        clientAddress);
  }

  @Override
  public boolean resendRegistration(
      UUID requestId, String channel, String contact, byte[] clientAddress) {
    return registration.resendRegistration(requestId, channel, contact, clientAddress);
  }

  @Override
  public boolean confirmRegistration(
      UUID requestId, String channel, String contact, String code, byte[] clientAddress) {
    return registration.confirmRegistration(requestId, channel, contact, code, clientAddress);
  }

  @Override
  public PasswordChangeResult changePassword(
      UUID requestId, String refresh, String currentPassword, String newPassword) {
    return security.changePassword(requestId, refresh, currentPassword, newPassword);
  }

  @Override
  public boolean requestPasswordRecovery(
      UUID requestId, String channel, String contact, byte[] clientAddress) {
    return security.requestPasswordRecovery(requestId, channel, contact, clientAddress);
  }

  @Override
  public boolean confirmPasswordRecovery(
      UUID requestId,
      String channel,
      String contact,
      String code,
      String newPassword,
      byte[] clientAddress,
      MfaProof mfaProof) {
    return security.confirmPasswordRecovery(
        requestId, channel, contact, code, newPassword, clientAddress, mfaProof);
  }

  @Override
  public MfaStatus mfaStatus(UUID requestId, String refresh) {
    return security.mfaStatus(requestId, refresh);
  }

  @Override
  public TotpEnrollmentStart startTotpEnrollment(
      UUID requestId, String refresh, byte[] clientAddress, MfaProof currentProof) {
    return security.startTotpEnrollment(requestId, refresh, clientAddress, currentProof);
  }

  @Override
  public MfaMutation confirmTotpEnrollment(
      UUID requestId,
      String refresh,
      String enrollmentChallenge,
      String totpCode,
      byte[] clientAddress) {
    return security.confirmTotpEnrollment(
        requestId, refresh, enrollmentChallenge, totpCode, clientAddress);
  }

  @Override
  public MfaMutation disableTotp(
      UUID requestId, String refresh, MfaProof proof, byte[] clientAddress) {
    return security.disableTotp(requestId, refresh, proof, clientAddress);
  }

  @Override
  public MfaMutation rotateRecoveryCodes(
      UUID requestId, String refresh, MfaProof proof, byte[] clientAddress) {
    return security.rotateRecoveryCodes(requestId, refresh, proof, clientAddress);
  }

  @Override
  public LoginResult completeMfaAuthentication(
      UUID requestId, String challenge, MfaProof proof, byte[] clientAddress) {
    return security.completeMfaAuthentication(requestId, challenge, proof, clientAddress);
  }

  @Override
  public LoginResult establishExternalIdentity(
      UUID requestId,
      byte[] evidenceId,
      Instant evidenceIssuedAt,
      VerifiedGoogleIdentity identity,
      byte[] clientAddress) {
    return security.establishExternalIdentity(
        requestId, evidenceId, evidenceIssuedAt, identity, clientAddress);
  }

  @Override
  public LoginResult linkExternalIdentity(
      UUID requestId,
      String refresh,
      byte[] evidenceId,
      Instant evidenceIssuedAt,
      VerifiedGoogleIdentity identity,
      byte[] clientAddress) {
    return security.linkExternalIdentity(
        requestId, refresh, evidenceId, evidenceIssuedAt, identity, clientAddress);
  }

  @Override
  public LoginResult unlinkExternalIdentity(UUID requestId, String refresh) {
    return security.unlinkExternalIdentity(requestId, refresh);
  }

  @Override
  public boolean googleIdentityLinked(UUID requestId, String refresh) {
    return security.googleIdentityLinked(requestId, refresh);
  }

  @Override
  public ErasureRequest requestSelfErasure(
      UUID requestId, String refresh, String confirmation, MfaProof mfaProof) {
    return security.requestSelfErasure(requestId, refresh, confirmation, mfaProof);
  }

  @Override
  public Profile profile(String refresh) {
    return profile.profile(refresh);
  }

  @Override
  public boolean updateProfile(
      UUID requestId, String refresh, String firstName, String lastName, String fatherName) {
    return profile.updateProfile(requestId, refresh, firstName, lastName, fatherName);
  }

  @Override
  public List<Contact> contacts(String refresh) {
    return profile.contacts(refresh);
  }

  @Override
  public UUID addContact(UUID requestId, String refresh, String type, String value, String locale) {
    return profile.addContact(requestId, refresh, type, value, locale);
  }

  @Override
  public boolean resendContactVerification(UUID requestId, String refresh, UUID contactId) {
    return profile.resendContactVerification(requestId, refresh, contactId);
  }

  @Override
  public boolean verifyContact(UUID requestId, String refresh, UUID contactId, String code) {
    return profile.verifyContact(requestId, refresh, contactId, code);
  }

  @Override
  public boolean setPrimaryContact(UUID requestId, String refresh, UUID contactId) {
    return profile.setPrimaryContact(requestId, refresh, contactId);
  }

  @Override
  public boolean removeContact(UUID requestId, String refresh, UUID contactId) {
    return profile.removeContact(requestId, refresh, contactId);
  }
}
