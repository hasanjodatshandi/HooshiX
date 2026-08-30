package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.*;
import java.util.*;
import org.jooq.*;

final class TenantInvitationPersistence extends TenantPersistenceSupport {
  TenantInvitationPersistence(DSLContext dsl, IntentFingerprintPort fingerprints) {
    super(dsl, fingerprints);
  }

  public InvitationResult createInvitation(
      UUID requestId,
      UUID actor,
      UUID tenantId,
      UUID contactId,
      byte[] fingerprintMaterial,
      Instant now,
      Instant expiresAt) {
    Replay replay = replay(requestId, "INVITE_EXISTING_USER", fingerprintMaterial);
    if (replay.present()) {
      Instant exp =
          instant(
              "SELECT expires_at FROM identity_invitation_query WHERE invitation_id=?",
              replay.resultId());
      return new InvitationResult(replay.resultId(), exp);
    }
    if (!"ACTIVE"
        .equals(string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenantId)))
      throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not active");
    var contact =
        dsl.fetchOne(
            "SELECT user_id FROM identity_contact WHERE contact_id=? AND verified_at IS NOT NULL AND removed_at IS NULL",
            contactId);
    if (contact == null)
      throw error(TenantError.VERIFIED_CONTACT_REQUIRED, "Verified contact is required");
    UUID target = contact.get("user_id", UUID.class);
    String userState = string("SELECT status FROM identity_user WHERE user_id=?", target);
    if (!"ACTIVE".equals(userState))
      throw error(TenantError.VERIFIED_CONTACT_REQUIRED, "Invitation target is unavailable");
    setTenant(tenantId);
    Boolean member =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_tenant_membership WHERE tenant_id=? AND user_id=? AND lifecycle<>'REMOVED')",
            tenantId,
            target);
    if (Boolean.TRUE.equals(member))
      throw error(TenantError.INVITATION_ALREADY_PENDING, "Target already has membership");
    UUID invitation = UUID.randomUUID();
    try {
      dsl.execute(
          "INSERT INTO identity_tenant_invitation(tenant_id,invitation_id,target_user_id,target_contact_id,invited_by_user_id,state,expires_at,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
          tenantId,
          invitation,
          target,
          contactId,
          actor,
          ts(expiresAt),
          ts(now),
          ts(now));
    } catch (org.jooq.exception.DataAccessException e) {
      if (isUnique(e))
        throw error(TenantError.INVITATION_ALREADY_PENDING, "Pending invitation already exists");
      throw e;
    }
    dsl.execute(
        "INSERT INTO identity_invitation_query(invitation_id,tenant_id,target_user_id,state,expires_at,updated_at) VALUES (?,?,?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        invitation,
        tenantId,
        target,
        ts(expiresAt),
        ts(now));
    dedup(
        requestId,
        "INVITE_EXISTING_USER",
        actor,
        invitation,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_INVITATION_CREATED", actor, now);
    return new InvitationResult(invitation, expiresAt);
  }

  public AcceptedInvitation acceptInvitation(
      UUID requestId, UUID userId, UUID invitationId, byte[] fingerprintMaterial, Instant now) {
    Replay replay = replay(requestId, "ACCEPT_INVITATION", fingerprintMaterial);
    if (replay.present()) {
      UUID tenant =
          uuid(
              "SELECT tenant_id FROM identity_user_membership_query WHERE membership_id=?",
              replay.resultId());
      return new AcceptedInvitation(tenant, replay.resultId());
    }
    var q =
        dsl.fetchOne(
            "SELECT tenant_id,target_user_id,state,expires_at FROM identity_invitation_query WHERE invitation_id=?",
            invitationId);
    if (q == null) throw error(TenantError.INVITATION_NOT_FOUND, "Invitation is not found");
    UUID tenant = q.get("tenant_id", UUID.class), target = q.get("target_user_id", UUID.class);
    if (!userId.equals(target))
      throw error(TenantError.INVITATION_TARGET_MISMATCH, "Invitation target mismatch");
    if (!"PENDING".equals(q.get("state", String.class)))
      throw error(TenantError.INVITATION_NOT_PENDING, "Invitation is not pending");
    Instant expires = q.get("expires_at", OffsetDateTime.class).toInstant();
    if (!now.isBefore(expires)) {
      setTenant(tenant);
      dsl.execute(
          "UPDATE identity_tenant_invitation SET state='EXPIRED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND invitation_id=? AND state='PENDING'",
          ts(now),
          tenant,
          invitationId);
      dsl.execute(
          "UPDATE identity_invitation_query SET state='EXPIRED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE invitation_id=?",
          ts(now),
          invitationId);
      throw error(TenantError.INVITATION_EXPIRED, "Invitation is expired");
    }
    if (!"ACTIVE".equals(string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenant)))
      throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not active");
    setTenant(tenant);
    var row =
        dsl.fetchOne(
            "SELECT state,target_user_id FROM identity_tenant_invitation WHERE tenant_id=? AND invitation_id=? FOR UPDATE",
            tenant,
            invitationId);
    if (row == null
        || !"PENDING".equals(row.get("state", String.class))
        || !userId.equals(row.get("target_user_id", UUID.class)))
      throw error(TenantError.INVITATION_NOT_PENDING, "Invitation is not pending");
    UUID membership = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO identity_tenant_membership(tenant_id,membership_id,user_id,lifecycle,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        tenant,
        membership,
        userId,
        ts(now),
        ts(now));
    dsl.execute(
        "UPDATE identity_tenant_invitation SET state='ACCEPTED',accepted_membership_id=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND invitation_id=?",
        membership,
        ts(now),
        tenant,
        invitationId);
    dsl.execute(
        "UPDATE identity_invitation_query SET state='ACCEPTED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE invitation_id=?",
        ts(now),
        invitationId);
    var t =
        dsl.fetchOne("SELECT name,slug,lifecycle FROM identity_tenant WHERE tenant_id=?", tenant);
    if (t == null)
      throw error(TenantError.SESSION_STATE_INVALID, "Tenant projection is unavailable");
    upsertMembershipQuery(
        userId,
        membership,
        tenant,
        "ACTIVE",
        t.get("lifecycle", String.class),
        t.get("name", String.class),
        t.get("slug", String.class),
        now);
    outbox(requestId, "PROVISION_MEMBER", tenant, membership, userId, null, now);
    dedup(
        requestId,
        "ACCEPT_INVITATION",
        userId,
        membership,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_INVITATION_ACCEPTED", userId, now);
    return new AcceptedInvitation(tenant, membership);
  }

  public List<InvitationSummary> listReceivedInvitations(UUID userId, Instant now) {
    expireInvitations(now, 200);
    return invitationSummaries(
        dsl.fetch(
            """
            SELECT q.invitation_id,q.tenant_id,t.name,t.slug,q.state,q.expires_at
            FROM identity_invitation_query q
            JOIN identity_tenant t ON t.tenant_id=q.tenant_id
            WHERE q.target_user_id=?
            ORDER BY q.updated_at DESC,q.invitation_id
            LIMIT 201
            """,
            userId));
  }

  public List<InvitationSummary> listTenantInvitations(UUID tenantId, Instant now) {
    expireInvitations(now, 200);
    return invitationSummaries(
        dsl.fetch(
            """
            SELECT q.invitation_id,q.tenant_id,t.name,t.slug,q.state,q.expires_at
            FROM identity_invitation_query q
            JOIN identity_tenant t ON t.tenant_id=q.tenant_id
            WHERE q.tenant_id=?
            ORDER BY q.updated_at DESC,q.invitation_id
            LIMIT 201
            """,
            tenantId));
  }

  public InvitationMutation declineInvitation(
      UUID requestId, UUID userId, UUID invitationId, byte[] fingerprintMaterial, Instant now) {
    Replay replay = replay(requestId, "DECLINE_INVITATION", fingerprintMaterial);
    if (replay.present()) return new InvitationMutation(replay.resultId(), "DECLINED");
    var query =
        dsl.fetchOne(
            "SELECT tenant_id,target_user_id,state,expires_at FROM identity_invitation_query WHERE invitation_id=? FOR UPDATE",
            invitationId);
    if (query == null) throw error(TenantError.INVITATION_NOT_FOUND, "Invitation is not found");
    if (!userId.equals(query.get("target_user_id", UUID.class)))
      throw error(TenantError.INVITATION_TARGET_MISMATCH, "Invitation target mismatch");
    UUID tenantId = query.get("tenant_id", UUID.class);
    setTenant(tenantId);
    String state = effectiveInvitationState(tenantId, invitationId, query, now);
    if ("EXPIRED".equals(state))
      throw error(TenantError.INVITATION_EXPIRED, "Invitation is expired");
    if (!"PENDING".equals(state))
      throw error(TenantError.INVITATION_NOT_PENDING, "Invitation is not pending");
    updateInvitationState(tenantId, invitationId, "DECLINED", now);
    dedup(
        requestId,
        "DECLINE_INVITATION",
        userId,
        invitationId,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_INVITATION_DECLINED", userId, now);
    return new InvitationMutation(invitationId, "DECLINED");
  }

  public InvitationMutation revokeInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID invitationId,
      byte[] fingerprintMaterial,
      Instant now) {
    Replay replay = replay(requestId, "REVOKE_INVITATION", fingerprintMaterial);
    if (replay.present()) return new InvitationMutation(replay.resultId(), "REVOKED");
    setTenant(tenantId);
    var invitation =
        dsl.fetchOne(
            "SELECT i.state,i.expires_at,q.target_user_id FROM identity_tenant_invitation i JOIN identity_invitation_query q ON q.invitation_id=i.invitation_id WHERE i.tenant_id=? AND i.invitation_id=? FOR UPDATE OF i,q",
            tenantId,
            invitationId);
    if (invitation == null)
      throw error(TenantError.INVITATION_NOT_FOUND, "Invitation is not found");
    String state = effectiveInvitationState(tenantId, invitationId, invitation, now);
    if ("EXPIRED".equals(state))
      throw error(TenantError.INVITATION_EXPIRED, "Invitation is expired");
    if (!"PENDING".equals(state))
      throw error(TenantError.INVITATION_NOT_PENDING, "Invitation is not pending");
    updateInvitationState(tenantId, invitationId, "REVOKED", now);
    dedup(
        requestId,
        "REVOKE_INVITATION",
        actorUserId,
        invitationId,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_INVITATION_REVOKED", actorUserId, now);
    return new InvitationMutation(invitationId, "REVOKED");
  }

  public InvitationResult reissueInvitation(
      UUID requestId,
      UUID actorUserId,
      UUID tenantId,
      UUID invitationId,
      byte[] fingerprintMaterial,
      Instant now,
      Instant expiresAt) {
    Replay replay = replay(requestId, "REISSUE_INVITATION", fingerprintMaterial);
    if (replay.present()) {
      Instant expiry =
          instant(
              "SELECT expires_at FROM identity_invitation_query WHERE invitation_id=?",
              replay.resultId());
      return new InvitationResult(replay.resultId(), expiry);
    }
    if (!"ACTIVE"
        .equals(string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenantId)))
      throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not active");
    setTenant(tenantId);
    var old =
        dsl.fetchOne(
            "SELECT state,expires_at,target_user_id,target_contact_id FROM identity_tenant_invitation WHERE tenant_id=? AND invitation_id=? FOR UPDATE",
            tenantId,
            invitationId);
    if (old == null) throw error(TenantError.INVITATION_NOT_FOUND, "Invitation is not found");
    String state = effectiveInvitationState(tenantId, invitationId, old, now);
    if (!Set.of("DECLINED", "EXPIRED", "REVOKED").contains(state))
      throw error(TenantError.INVITATION_REISSUE_FORBIDDEN, "Invitation cannot be reissued");
    UUID targetUser = old.get("target_user_id", UUID.class);
    UUID targetContact = old.get("target_contact_id", UUID.class);
    Boolean contactActive =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_contact WHERE contact_id=? AND user_id=? AND verified_at IS NOT NULL AND removed_at IS NULL)",
            targetContact,
            targetUser);
    if (!Boolean.TRUE.equals(contactActive))
      throw error(TenantError.VERIFIED_CONTACT_REQUIRED, "Verified contact is required");
    Boolean member =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_tenant_membership WHERE tenant_id=? AND user_id=? AND lifecycle<>'REMOVED')",
            tenantId,
            targetUser);
    if (Boolean.TRUE.equals(member))
      throw error(TenantError.INVITATION_REISSUE_FORBIDDEN, "Target already has membership");
    UUID reissued = UUID.randomUUID();
    try {
      dsl.execute(
          "INSERT INTO identity_tenant_invitation(tenant_id,invitation_id,target_user_id,target_contact_id,invited_by_user_id,state,expires_at,reissued_from_invitation_id,created_at,updated_at) VALUES (?,?,?,?,?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE),?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
          tenantId,
          reissued,
          targetUser,
          targetContact,
          actorUserId,
          ts(expiresAt),
          invitationId,
          ts(now),
          ts(now));
    } catch (org.jooq.exception.DataAccessException e) {
      if (isUnique(e))
        throw error(TenantError.INVITATION_ALREADY_PENDING, "Pending invitation already exists");
      throw e;
    }
    dsl.execute(
        "INSERT INTO identity_invitation_query(invitation_id,tenant_id,target_user_id,state,expires_at,updated_at) VALUES (?,?,?,'PENDING',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        reissued,
        tenantId,
        targetUser,
        ts(expiresAt),
        ts(now));
    dedup(
        requestId,
        "REISSUE_INVITATION",
        actorUserId,
        reissued,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_INVITATION_REISSUED", actorUserId, now);
    return new InvitationResult(reissued, expiresAt);
  }

  public int expireInvitations(Instant now, int batch) {
    if (batch < 1 || batch > 200) throw new IllegalArgumentException("Expiry batch is invalid");
    var due =
        dsl.fetch(
            "SELECT invitation_id,tenant_id,target_user_id FROM identity_invitation_query WHERE state='PENDING' AND expires_at<=CAST(? AS TIMESTAMP WITH TIME ZONE) ORDER BY expires_at,invitation_id FOR UPDATE SKIP LOCKED LIMIT ?",
            ts(now),
            batch);
    for (org.jooq.Record row : due) {
      UUID tenantId = row.get("tenant_id", UUID.class);
      UUID invitationId = row.get("invitation_id", UUID.class);
      setTenant(tenantId);
      updateInvitationState(tenantId, invitationId, "EXPIRED", now);
      audit("IDENTITY_TENANT_INVITATION_EXPIRED", row.get("target_user_id", UUID.class), now);
    }
    return due.size();
  }
}
