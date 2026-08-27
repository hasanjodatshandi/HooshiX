package com.sajtech.identity.application.tenant.port.in;

import com.sajtech.identity.application.tenant.model.AcceptedInvitation;
import com.sajtech.identity.application.tenant.model.InvitationMutation;
import com.sajtech.identity.application.tenant.model.InvitationResult;
import com.sajtech.identity.application.tenant.model.InvitationSummary;
import com.sajtech.identity.application.tenant.model.SelectableTenantList;
import com.sajtech.identity.application.tenant.model.TenantCreation;
import com.sajtech.identity.application.tenant.model.TenantLifecycleMutation;
import com.sajtech.identity.application.tenant.model.TenantSelection;
import java.util.List;
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

  TenantLifecycleMutation suspendTenant(UUID requestId, String refreshCredential, UUID tenantId);

  TenantLifecycleMutation resumeTenant(UUID requestId, String refreshCredential, UUID tenantId);

  TenantLifecycleMutation deleteTenant(UUID requestId, String refreshCredential, UUID tenantId);

  TenantLifecycleMutation restoreTenant(UUID requestId, String refreshCredential, UUID tenantId);

  List<InvitationSummary> listReceivedInvitations(String refreshCredential);

  List<InvitationSummary> listTenantInvitations(String refreshCredential);

  InvitationMutation declineInvitation(UUID requestId, String refreshCredential, UUID invitationId);

  InvitationMutation revokeInvitation(UUID requestId, String refreshCredential, UUID invitationId);

  InvitationResult reissueInvitation(UUID requestId, String refreshCredential, UUID invitationId);
}
