package com.sajtech.identity.application.tenant.port.in;

import com.sajtech.identity.application.tenant.model.AcceptedInvitation;
import com.sajtech.identity.application.tenant.model.InvitationResult;
import com.sajtech.identity.application.tenant.model.SelectableTenantList;
import com.sajtech.identity.application.tenant.model.TenantCreation;
import com.sajtech.identity.application.tenant.model.TenantSelection;
import java.util.UUID;

public interface TenantLifecycle {
  TenantCreation createTenant(UUID requestId, String refreshCredential, String name, String slug);

  SelectableTenantList listSelectable(String refreshCredential);

  TenantSelection selectTenant(
      UUID requestId, String refreshCredential, UUID membershipId, String audience);

  InvitationResult inviteExistingUser(
      UUID requestId, String refreshCredential, UUID targetContactId);

  AcceptedInvitation acceptInvitation(UUID requestId, String refreshCredential, UUID invitationId);

  void removeMembership(UUID requestId, String refreshCredential, UUID targetMembershipId);
}
