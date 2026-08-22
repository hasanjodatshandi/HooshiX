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

  String issueAudienceToken(UUID requestId, String refresh, String audience);

  void logout(UUID requestId, String refresh);

  enum SessionMode {
    AUTHENTICATED_ONBOARDING,
    TENANT_AUTHENTICATED
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
      UUID selectedMembershipId) {}

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
}
