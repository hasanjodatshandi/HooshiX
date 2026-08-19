package com.sajtech.identity.application.tenant.port.out;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.Instant;
import java.util.*;

public interface TenantStore {
  TenantCreation createTenant(
      UUID requestId,
      UUID userId,
      String name,
      String slug,
      byte[] fingerprintMaterial,
      Instant now);

  List<SelectableTenant> listSelectable(UUID userId);

  UUID lastSelectedMembership(UUID userId);

  boolean isSelectable(UUID userId, UUID tenantId, UUID membershipId);

  void selectContext(
      LockedRefreshCredential current,
      UUID membershipId,
      UUID tenantId,
      UUID newCredentialId,
      RefreshDigest nextDigest,
      Instant now,
      Instant nextIdleExpiresAt);

  InvitationResult createInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID targetContactId,
      byte[] fingerprintMaterial,
      Instant now,
      Instant expiresAt);

  AcceptedInvitation acceptInvitation(
      UUID requestId, UUID userId, UUID invitationId, byte[] fingerprintMaterial, Instant now);

  RemovalPreparation createRemovalIntent(
      UUID requestId,
      UUID actorUserId,
      UUID selectedTenantId,
      UUID selectedMembershipId,
      UUID targetMembershipId,
      byte[] fingerprintMaterial,
      Instant now);

  void commitMembershipRemoval(UUID requestId, Instant now);

  void enqueueRemovalCancel(UUID requestId, Instant now);

  List<AuthorizationOutboxItem> claimAuthorizationOutbox(
      Instant now, int batch, Instant leaseUntil);

  void completeAuthorizationOutbox(UUID outboxId, Instant now);

  void rescheduleAuthorizationOutbox(
      UUID outboxId, Instant now, Instant nextAttempt, int attemptCount, boolean definitiveFailure);
}
