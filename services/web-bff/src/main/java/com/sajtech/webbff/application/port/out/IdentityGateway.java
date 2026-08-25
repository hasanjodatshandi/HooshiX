package com.sajtech.webbff.application.port.out;

import java.time.Instant;
import java.util.*;

public interface IdentityGateway {
  LoginResult login(
      UUID requestId, String channel, String contact, String password, byte[] clientAddress);

  RegisterResult register(
      UUID requestId,
      String channel,
      String contact,
      String password,
      String locale,
      String firstName,
      String lastName,
      String fatherName,
      byte[] clientAddress);

  boolean resendRegistration(UUID requestId, String channel, String contact, byte[] clientAddress);

  boolean confirmRegistration(
      UUID requestId, String channel, String contact, String code, byte[] clientAddress);

  ListResult listTenants(String refresh);

  SelectResult selectTenant(UUID requestId, String refresh, UUID membershipId, String audience);

  TenantCreated createTenant(UUID requestId, String refresh, String name, String slug);

  InvitationCreated invite(UUID requestId, String refresh, UUID contactId);

  AcceptedInvitation accept(UUID requestId, String refresh, UUID invitationId);

  void removeMembership(UUID requestId, String refresh, UUID membershipId);

  Profile profile(String refresh);

  boolean updateProfile(
      UUID requestId, String refresh, String firstName, String lastName, String fatherName);

  List<Contact> contacts(String refresh);

  UUID addContact(UUID requestId, String refresh, String type, String value, String locale);

  boolean resendContactVerification(UUID requestId, String refresh, UUID contactId);

  boolean verifyContact(UUID requestId, String refresh, UUID contactId, String code);

  boolean setPrimaryContact(UUID requestId, String refresh, UUID contactId);

  boolean removeContact(UUID requestId, String refresh, UUID contactId);

  String issueAudienceToken(UUID requestId, String refresh, String audience);

  void logout(UUID requestId, String refresh);

  PasswordChangeResult changePassword(
      UUID requestId, String refresh, String currentPassword, String newPassword);

  boolean requestPasswordRecovery(
      UUID requestId, String channel, String contact, byte[] clientAddress);

  boolean confirmPasswordRecovery(
      UUID requestId,
      String channel,
      String contact,
      String code,
      String newPassword,
      byte[] clientAddress,
      MfaProof mfaProof);

  default boolean confirmPasswordRecovery(
      UUID requestId,
      String channel,
      String contact,
      String code,
      String newPassword,
      byte[] clientAddress) {
    return confirmPasswordRecovery(
        requestId, channel, contact, code, newPassword, clientAddress, null);
  }

  MfaStatus mfaStatus(UUID requestId, String refresh);

  TotpEnrollmentStart startTotpEnrollment(
      UUID requestId, String refresh, byte[] clientAddress, MfaProof currentProof);

  MfaMutation confirmTotpEnrollment(
      UUID requestId,
      String refresh,
      String enrollmentChallenge,
      String totpCode,
      byte[] clientAddress);

  MfaMutation disableTotp(UUID requestId, String refresh, MfaProof proof, byte[] clientAddress);

  MfaMutation rotateRecoveryCodes(
      UUID requestId, String refresh, MfaProof proof, byte[] clientAddress);

  LoginResult completeMfaAuthentication(
      UUID requestId, String challenge, MfaProof proof, byte[] clientAddress);

  enum SessionMode {
    AUTHENTICATED_ONBOARDING,
    TENANT_AUTHENTICATED,
    MFA_REQUIRED
  }

  record RegisterResult(boolean accepted) {}

  record LoginResult(
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      SessionMode mode,
      UUID selectedTenantId,
      UUID selectedMembershipId,
      String mfaChallenge) {
    public LoginResult(
        UUID userId,
        String identitySessionId,
        UUID refreshFamilyId,
        String refreshCredential,
        Instant idleExpiresAt,
        Instant absoluteExpiresAt,
        SessionMode mode,
        UUID selectedTenantId,
        UUID selectedMembershipId) {
      this(
          userId,
          identitySessionId,
          refreshFamilyId,
          refreshCredential,
          idleExpiresAt,
          absoluteExpiresAt,
          mode,
          selectedTenantId,
          selectedMembershipId,
          null);
    }
  }

  record TenantChoice(UUID tenantId, UUID membershipId, String name, String slug) {}

  record ListResult(List<TenantChoice> tenants, UUID suggestedMembershipId) {
    public ListResult {
      tenants = List.copyOf(tenants);
    }
  }

  record SelectResult(
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant idleExpiresAt,
      Instant absoluteExpiresAt,
      UUID tenantId,
      UUID membershipId) {}

  record TenantCreated(UUID tenantId, UUID membershipId, String lifecycle) {}

  record InvitationCreated(UUID invitationId, Instant expiresAt) {}

  record AcceptedInvitation(UUID tenantId, UUID membershipId) {}

  record Profile(UUID id, String firstName, String lastName, String fatherName) {}

  record Contact(UUID id, String type, String value, boolean verified, boolean primary) {}

  record PasswordChangeResult(
      String refreshCredential, Instant idleExpiresAt, Instant absoluteExpiresAt) {}

  record MfaProof(String type, String code) {}

  record MfaStatus(boolean totpEnabled, int recoveryCodesRemaining) {}

  record TotpEnrollmentStart(
      String enrollmentChallenge, String base32Secret, String otpauthUri, Instant expiresAt) {}

  record MfaMutation(LoginResult session, List<String> recoveryCodes) {
    public MfaMutation {
      recoveryCodes = List.copyOf(recoveryCodes);
    }
  }
}
