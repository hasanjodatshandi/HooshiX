package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.port.out.AuthenticationTenantSelectionPort;
import com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.application.tenant.port.out.TenantStore;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;

public final class JooqTenantStore extends TenantSelectionPersistence
    implements TenantStore,
        TenantContextValidationPort,
        AuthenticationTenantSelectionPort,
        AuthorizationOutboxTelemetryQuery {
  private final TenantInvitationPersistence invitations;
  private final TenantLifecyclePersistence lifecycle;
  private final TenantAuthorizationOutboxPersistence authorizationOutbox;

  public JooqTenantStore(DSLContext dsl, IntentFingerprintPort fingerprints) {
    super(dsl, fingerprints);
    Objects.requireNonNull(dsl);
    Objects.requireNonNull(fingerprints);
    invitations = new TenantInvitationPersistence(dsl, fingerprints);
    lifecycle = new TenantLifecyclePersistence(dsl, fingerprints, this);
    authorizationOutbox = new TenantAuthorizationOutboxPersistence(dsl, fingerprints);
  }

  @Override
  public InvitationResult createInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID targetContactId,
      byte[] fingerprintMaterial,
      Instant now,
      Instant expiresAt) {
    return invitations.createInvitation(
        requestId, actorUserId, tenantId, targetContactId, fingerprintMaterial, now, expiresAt);
  }

  @Override
  public AcceptedInvitation acceptInvitation(
      UUID requestId, UUID userId, UUID invitationId, byte[] fingerprintMaterial, Instant now) {
    return invitations.acceptInvitation(requestId, userId, invitationId, fingerprintMaterial, now);
  }

  @Override
  public RemovalPreparation createRemovalIntent(
      UUID requestId,
      UUID actorUserId,
      UUID selectedTenantId,
      UUID selectedMembershipId,
      UUID targetMembershipId,
      byte[] fingerprintMaterial,
      Instant now) {
    return lifecycle.createRemovalIntent(
        requestId,
        actorUserId,
        selectedTenantId,
        selectedMembershipId,
        targetMembershipId,
        fingerprintMaterial,
        now);
  }

  @Override
  public void commitMembershipRemoval(UUID requestId, Instant now) {
    lifecycle.commitMembershipRemoval(requestId, now);
  }

  @Override
  public void enqueueRemovalCancel(UUID requestId, Instant now) {
    lifecycle.enqueueRemovalCancel(requestId, now);
  }

  @Override
  public TenantLifecycleMutation requestTenantLifecycle(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      String expectedLifecycle,
      String targetLifecycle,
      byte[] fingerprintMaterial,
      Instant now) {
    return lifecycle.requestTenantLifecycle(
        requestId,
        actorUserId,
        tenantId,
        expectedLifecycle,
        targetLifecycle,
        fingerprintMaterial,
        now);
  }

  @Override
  public TenantLifecycleMutation restoreTenant(
      UUID requestId, UUID actorUserId, UUID tenantId, byte[] fingerprintMaterial, Instant now) {
    return lifecycle.restoreTenant(requestId, actorUserId, tenantId, fingerprintMaterial, now);
  }

  @Override
  public List<InvitationSummary> listReceivedInvitations(UUID userId, Instant now) {
    return invitations.listReceivedInvitations(userId, now);
  }

  @Override
  public List<InvitationSummary> listTenantInvitations(UUID tenantId, Instant now) {
    return invitations.listTenantInvitations(tenantId, now);
  }

  @Override
  public InvitationMutation declineInvitation(
      UUID requestId, UUID userId, UUID invitationId, byte[] fingerprintMaterial, Instant now) {
    return invitations.declineInvitation(requestId, userId, invitationId, fingerprintMaterial, now);
  }

  @Override
  public InvitationMutation revokeInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID invitationId,
      byte[] fingerprintMaterial,
      Instant now) {
    return invitations.revokeInvitation(
        requestId, actorUserId, tenantId, invitationId, fingerprintMaterial, now);
  }

  @Override
  public InvitationResult reissueInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID invitationId,
      byte[] fingerprintMaterial,
      Instant now,
      Instant expiresAt) {
    return invitations.reissueInvitation(
        requestId, actorUserId, tenantId, invitationId, fingerprintMaterial, now, expiresAt);
  }

  @Override
  public int expireInvitations(Instant now, int batch) {
    return invitations.expireInvitations(now, batch);
  }

  @Override
  public List<AuthorizationOutboxItem> claimAuthorizationOutbox(
      Instant now, int batch, Instant leaseUntil) {
    return authorizationOutbox.claimAuthorizationOutbox(now, batch, leaseUntil);
  }

  @Override
  public Optional<Instant> oldestUnresolvedAuthorizationOutboxCreatedAt() {
    return authorizationOutbox.oldestUnresolvedAuthorizationOutboxCreatedAt();
  }

  @Override
  public void completeAuthorizationOutbox(UUID outboxId, Instant now) {
    authorizationOutbox.completeAuthorizationOutbox(outboxId, now);
  }

  @Override
  public void rescheduleAuthorizationOutbox(
      UUID outboxId,
      Instant now,
      Instant nextAttempt,
      int attemptCount,
      boolean definitiveFailure) {
    authorizationOutbox.rescheduleAuthorizationOutbox(
        outboxId, now, nextAttempt, attemptCount, definitiveFailure);
  }
}
