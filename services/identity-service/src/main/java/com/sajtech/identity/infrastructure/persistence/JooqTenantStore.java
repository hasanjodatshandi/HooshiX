package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.AuthenticationTenantSelectionPort;
import com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import com.sajtech.identity.application.tenant.port.out.TenantStore;
import java.time.*;
import java.util.*;
import org.jooq.*;

public final class JooqTenantStore
    implements TenantStore,
        TenantContextValidationPort,
        AuthenticationTenantSelectionPort,
        AuthorizationOutboxTelemetryQuery {
  private final DSLContext dsl;
  private final IntentFingerprintPort fingerprints;

  public JooqTenantStore(DSLContext dsl, IntentFingerprintPort fingerprints) {
    this.dsl = Objects.requireNonNull(dsl);
    this.fingerprints = Objects.requireNonNull(fingerprints);
  }

  @Override
  public TenantCreation createTenant(
      UUID requestId,
      UUID userId,
      String name,
      String slug,
      byte[] fingerprintMaterial,
      Instant now) {
    Replay replay = replay(requestId, "CREATE_TENANT", fingerprintMaterial);
    if (replay.present()) {
      UUID membership = membershipFor(replay.resultId(), userId);
      return new TenantCreation(
          replay.resultId(),
          membership,
          string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", replay.resultId()));
    }
    UUID tenantId = UUID.randomUUID(), membershipId = UUID.randomUUID();
    try {
      dsl.execute(
          "INSERT INTO identity_tenant(tenant_id,name,slug,lifecycle,creator_user_id,version,created_at,updated_at) VALUES (?,?,?,'PROVISIONING',?,1,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
          tenantId,
          name,
          slug,
          userId,
          ts(now),
          ts(now));
    } catch (org.jooq.exception.DataAccessException e) {
      if (isUnique(e)) throw error(TenantError.TENANT_SLUG_CONFLICT, "Tenant slug is unavailable");
      throw e;
    }
    setTenant(tenantId);
    dsl.execute(
        "INSERT INTO identity_tenant_membership(tenant_id,membership_id,user_id,lifecycle,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        tenantId,
        membershipId,
        userId,
        ts(now),
        ts(now));
    upsertMembershipQuery(
        userId, membershipId, tenantId, "ACTIVE", "PROVISIONING", name, slug, now);
    outbox(requestId, "PROVISION_OWNER", tenantId, membershipId, userId, null, now);
    dedup(
        requestId,
        "CREATE_TENANT",
        userId,
        tenantId,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_CREATED", userId, now);
    return new TenantCreation(tenantId, membershipId, "PROVISIONING");
  }

  @Override
  public List<SelectableTenant> listSelectable(UUID userId) {
    var rows =
        dsl.fetch(
            "SELECT tenant_id,membership_id,tenant_name,tenant_slug FROM identity_user_membership_query WHERE user_id=? AND membership_lifecycle='ACTIVE' AND tenant_lifecycle='ACTIVE' ORDER BY tenant_id,membership_id LIMIT 201",
            userId);
    if (rows.size() > 200)
      throw error(TenantError.SESSION_STATE_INVALID, "Selectable membership limit exceeded");
    List<SelectableTenant> out = new ArrayList<>();
    for (org.jooq.Record r : rows) {
      UUID tenant = r.get("tenant_id", UUID.class), membership = r.get("membership_id", UUID.class);
      if (isSelectable(userId, tenant, membership))
        out.add(
            new SelectableTenant(
                tenant,
                membership,
                r.get("tenant_name", String.class),
                r.get("tenant_slug", String.class)));
    }
    return List.copyOf(out);
  }

  @Override
  public UUID lastSelectedMembership(UUID userId) {
    return uuid(
        "SELECT last_selected_membership_id FROM identity_user_tenant_preference WHERE user_id=?",
        userId);
  }

  @Override
  public AuthenticationTenantSelection resolveAfterPrimaryAuthentication(UUID userId) {
    List<SelectableTenant> selectable = listSelectable(userId);
    if (selectable.isEmpty()) return AuthenticationTenantSelection.onboarding();
    if (selectable.size() == 1) {
      SelectableTenant only = selectable.getFirst();
      return AuthenticationTenantSelection.tenant(only.tenantId(), only.membershipId());
    }
    UUID preferred = lastSelectedMembership(userId);
    if (preferred == null) return AuthenticationTenantSelection.onboarding();
    for (SelectableTenant tenant : selectable)
      if (preferred.equals(tenant.membershipId()))
        return AuthenticationTenantSelection.tenant(tenant.tenantId(), tenant.membershipId());
    return AuthenticationTenantSelection.onboarding();
  }

  @Override
  public boolean isSelectable(UUID userId, UUID tenantId, UUID membershipId) {
    String tenant = string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenantId);
    if (!"ACTIVE".equals(tenant)) return false;
    setTenant(tenantId);
    Boolean ok =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_tenant_membership WHERE tenant_id=? AND membership_id=? AND user_id=? AND lifecycle='ACTIVE')",
            tenantId,
            membershipId,
            userId);
    return Boolean.TRUE.equals(ok);
  }

  @Override
  public void selectContext(
      LockedRefreshCredential current,
      UUID membershipId,
      UUID tenantId,
      UUID newCredentialId,
      RefreshDigest nextDigest,
      Instant now,
      Instant nextIdle) {
    if (!isSelectable(current.userId(), tenantId, membershipId))
      throw error(TenantError.MEMBERSHIP_NOT_ACTIVE, "Membership is not selectable");
    int retired =
        dsl.execute(
            "UPDATE identity_refresh_credential SET state='ROTATED',retired_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE credential_id=? AND refresh_family_id=? AND state='ACTIVE'",
            ts(now),
            current.credentialId(),
            current.refreshFamilyId());
    if (retired != 1) throw error(TenantError.SESSION_STATE_INVALID, "Refresh state changed");
    dsl.execute(
        "INSERT INTO identity_refresh_credential(credential_id,refresh_family_id,token_digest,digest_key_id,digest_version,state,issued_at) VALUES (?,?,?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE))",
        newCredentialId,
        current.refreshFamilyId(),
        nextDigest.digest(),
        nextDigest.keyId(),
        nextDigest.version(),
        ts(now));
    int updated =
        dsl.execute(
            "UPDATE identity_refresh_family SET session_mode='TENANT_AUTHENTICATED',selected_tenant_id=?,selected_membership_id=?,last_activity_at=CAST(? AS TIMESTAMP WITH TIME ZONE),idle_expires_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE refresh_family_id=? AND state='ACTIVE'",
            tenantId,
            membershipId,
            ts(now),
            ts(nextIdle),
            ts(now),
            current.refreshFamilyId());
    if (updated != 1) throw error(TenantError.SESSION_STATE_INVALID, "Session state changed");
    dsl.execute(
        "INSERT INTO identity_user_tenant_preference(user_id,last_selected_membership_id,updated_at) VALUES (?,?,CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT(user_id) DO UPDATE SET last_selected_membership_id=EXCLUDED.last_selected_membership_id,updated_at=EXCLUDED.updated_at",
        current.userId(),
        membershipId,
        ts(now));
    audit("IDENTITY_TENANT_SELECTED", current.userId(), now);
  }

  @Override
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

  @Override
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

  @Override
  public RemovalPreparation createRemovalIntent(
      UUID requestId,
      UUID actorUserId,
      UUID selectedTenantId,
      UUID selectedMembershipId,
      UUID targetMembershipId,
      byte[] fingerprintMaterial,
      Instant now) {
    Replay replay = replay(requestId, "REMOVE_MEMBERSHIP", fingerprintMaterial);
    if (replay.present()) {
      var r =
          dsl.fetchOne(
              "SELECT tenant_id,membership_id FROM identity_membership_removal_intent WHERE request_id=?",
              requestId);
      if (r == null)
        throw error(TenantError.SESSION_STATE_INVALID, "Removal replay state is unavailable");
      return new RemovalPreparation(
          requestId,
          r.get("tenant_id", UUID.class),
          r.get("membership_id", UUID.class),
          selectedMembershipId,
          r.get("membership_id", UUID.class).equals(selectedMembershipId));
    }
    if (!isSelectable(actorUserId, selectedTenantId, selectedMembershipId))
      throw error(TenantError.INVALID_SESSION, "Actor membership is not selectable");
    setTenant(selectedTenantId);
    String target =
        string(
            "SELECT lifecycle FROM identity_tenant_membership WHERE tenant_id=? AND membership_id=?",
            selectedTenantId,
            targetMembershipId);
    if (!"ACTIVE".equals(target))
      throw error(TenantError.MEMBERSHIP_NOT_ACTIVE, "Target membership is not active");
    dsl.execute(
        "INSERT INTO identity_membership_removal_intent(request_id,tenant_id,membership_id,requested_by_user_id,state,created_at,updated_at) VALUES (?,?,?,?,'PREPARING',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        requestId,
        selectedTenantId,
        targetMembershipId,
        actorUserId,
        ts(now),
        ts(now));
    dedup(
        requestId,
        "REMOVE_MEMBERSHIP",
        actorUserId,
        targetMembershipId,
        fingerprints.digest(fingerprintMaterial),
        now);
    return new RemovalPreparation(
        requestId,
        selectedTenantId,
        targetMembershipId,
        selectedMembershipId,
        targetMembershipId.equals(selectedMembershipId));
  }

  @Override
  public void commitMembershipRemoval(UUID requestId, Instant now) {
    var intent =
        dsl.fetchOne(
            "SELECT tenant_id,membership_id,state FROM identity_membership_removal_intent WHERE request_id=? FOR UPDATE",
            requestId);
    if (intent == null) throw error(TenantError.INVALID_ARGUMENT, "Removal intent is missing");
    String state = intent.get("state", String.class);
    if ("LOCAL_COMMITTED".equals(state) || "FINALIZED".equals(state)) return;
    if (!"PREPARING".equals(state) && !"PREPARED".equals(state))
      throw error(TenantError.SESSION_STATE_INVALID, "Removal intent state is invalid");
    UUID tenant = intent.get("tenant_id", UUID.class),
        membership = intent.get("membership_id", UUID.class);
    setTenant(tenant);
    var member =
        dsl.fetchOne(
            "SELECT user_id,lifecycle FROM identity_tenant_membership WHERE tenant_id=? AND membership_id=? FOR UPDATE",
            tenant,
            membership);
    if (member == null) throw error(TenantError.MEMBERSHIP_NOT_ACTIVE, "Membership is not found");
    if ("ACTIVE".equals(member.get("lifecycle", String.class))) {
      dsl.execute(
          "UPDATE identity_tenant_membership SET lifecycle='REMOVED',removed_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND membership_id=?",
          ts(now),
          ts(now),
          tenant,
          membership);
      dsl.execute(
          "UPDATE identity_user_membership_query SET membership_lifecycle='REMOVED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE membership_id=?",
          ts(now),
          membership);
    }
    dsl.execute(
        "UPDATE identity_membership_removal_intent SET state='LOCAL_COMMITTED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE request_id=?",
        ts(now),
        requestId);
    outbox(
        requestId,
        "FINALIZE_MEMBERSHIP_REMOVAL",
        tenant,
        membership,
        member.get("user_id", UUID.class),
        null,
        now);
    audit("IDENTITY_MEMBERSHIP_REMOVED", member.get("user_id", UUID.class), now);
  }

  @Override
  public void enqueueRemovalCancel(UUID requestId, Instant now) {
    var i =
        dsl.fetchOne(
            "SELECT tenant_id,membership_id,requested_by_user_id,state FROM identity_membership_removal_intent WHERE request_id=? FOR UPDATE",
            requestId);
    if (i == null) return;
    String state = i.get("state", String.class);
    if ("LOCAL_COMMITTED".equals(state) || "FINALIZED".equals(state)) return;
    if (!"PREPARING".equals(state) && !"PREPARED".equals(state) && !"CANCEL_PENDING".equals(state))
      return;
    dsl.execute(
        "UPDATE identity_membership_removal_intent SET state='CANCEL_PENDING',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE request_id=?",
        ts(now),
        requestId);
    outbox(
        requestId,
        "CANCEL_MEMBERSHIP_REMOVAL",
        i.get("tenant_id", UUID.class),
        i.get("membership_id", UUID.class),
        i.get("requested_by_user_id", UUID.class),
        null,
        now);
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
    String operation =
        switch (targetLifecycle) {
          case "SUSPENDED" -> "SUSPEND_TENANT";
          case "DELETING" -> "DELETE_TENANT";
          case "ACTIVE" -> "RESUME_TENANT";
          default -> throw error(TenantError.INVALID_ARGUMENT, "Unsupported lifecycle target");
        };
    Replay replay = replay(requestId, operation, fingerprintMaterial);
    if (replay.present()) return lifecycleResult(requestId, replay.resultId(), targetLifecycle);
    var tenant =
        dsl.fetchOne(
            "SELECT lifecycle FROM identity_tenant WHERE tenant_id=? FOR UPDATE", tenantId);
    if (tenant == null) throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not found");
    String current = tenant.get("lifecycle", String.class);
    if (!expectedLifecycle.equals(current))
      throw error(TenantError.TENANT_LIFECYCLE_INVALID, "Tenant lifecycle transition is invalid");
    ensureNoPendingLifecycle(tenantId);
    outbox(requestId, "APPLY_TENANT_LIFECYCLE", tenantId, null, actorUserId, targetLifecycle, now);
    dedup(
        requestId, operation, actorUserId, tenantId, fingerprints.digest(fingerprintMaterial), now);
    audit("IDENTITY_TENANT_" + targetLifecycle + "_REQUESTED", actorUserId, now);
    return new TenantLifecycleMutation(
        tenantId, current, "DELETING".equals(targetLifecycle) ? "DELETED" : targetLifecycle, true);
  }

  @Override
  public TenantLifecycleMutation restoreTenant(
      UUID requestId, UUID actorUserId, UUID tenantId, byte[] fingerprintMaterial, Instant now) {
    Replay replay = replay(requestId, "RESTORE_TENANT", fingerprintMaterial);
    if (replay.present()) return lifecycleResult(requestId, replay.resultId(), "ACTIVE");
    var tenant =
        dsl.fetchOne(
            "SELECT lifecycle,purge_started_at FROM identity_tenant WHERE tenant_id=? FOR UPDATE",
            tenantId);
    if (tenant == null) throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not found");
    if (!"DELETED".equals(tenant.get("lifecycle", String.class)))
      throw error(TenantError.TENANT_LIFECYCLE_INVALID, "Tenant is not deleted");
    if (tenant.get("purge_started_at", OffsetDateTime.class) != null)
      throw error(TenantError.TENANT_RESTORE_FORBIDDEN, "Tenant purge has started");
    ensureNoPendingLifecycle(tenantId);
    dsl.execute(
        "UPDATE identity_tenant SET lifecycle='PROVISIONING',deleted_at=NULL,version=version+1,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
        ts(now),
        tenantId);
    dsl.execute(
        "UPDATE identity_user_membership_query SET tenant_lifecycle='PROVISIONING',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
        ts(now),
        tenantId);
    outbox(requestId, "APPLY_TENANT_LIFECYCLE", tenantId, null, actorUserId, "ACTIVE", now);
    dedup(
        requestId,
        "RESTORE_TENANT",
        actorUserId,
        tenantId,
        fingerprints.digest(fingerprintMaterial),
        now);
    audit("IDENTITY_TENANT_RESTORE_REQUESTED", actorUserId, now);
    return new TenantLifecycleMutation(tenantId, "PROVISIONING", "ACTIVE", true);
  }

  @Override
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

  @Override
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

  @Override
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

  @Override
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

  @Override
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

  @Override
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

  @Override
  public List<AuthorizationOutboxItem> claimAuthorizationOutbox(
      Instant now, int batch, Instant leaseUntil) {
    if (batch < 1 || batch > 32)
      throw new IllegalArgumentException("Authorization outbox batch is invalid");
    var rows =
        dsl.fetch(
            """
 SELECT outbox_id,request_id,operation,tenant_id,membership_id,user_id,lifecycle,attempt_count FROM identity_authorization_outbox
 WHERE (state='PENDING' AND next_attempt_at<=CAST(? AS TIMESTAMP WITH TIME ZONE)) OR (state='DISPATCHING' AND lease_until<=CAST(? AS TIMESTAMP WITH TIME ZONE))
 ORDER BY next_attempt_at,outbox_id FOR UPDATE SKIP LOCKED LIMIT ?
 """,
            ts(now),
            ts(now),
            batch);
    List<AuthorizationOutboxItem> out = new ArrayList<>();
    for (org.jooq.Record r : rows) {
      UUID id = r.get("outbox_id", UUID.class);
      dsl.execute(
          "UPDATE identity_authorization_outbox SET state='DISPATCHING',lease_until=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
          ts(leaseUntil),
          ts(now),
          id);
      out.add(
          new AuthorizationOutboxItem(
              id,
              r.get("request_id", UUID.class),
              r.get("operation", String.class),
              r.get("tenant_id", UUID.class),
              r.get("membership_id", UUID.class),
              r.get("user_id", UUID.class),
              r.get("lifecycle", String.class),
              r.get("attempt_count", Integer.class)));
    }
    return List.copyOf(out);
  }

  @Override
  public Optional<Instant> oldestUnresolvedAuthorizationOutboxCreatedAt() {
    var row =
        dsl.fetchOne(
            """
            SELECT created_at
            FROM identity_authorization_outbox
            WHERE state IN ('PENDING','DISPATCHING')
            ORDER BY created_at, outbox_id
            LIMIT 1
            """);
    if (row == null) return Optional.empty();
    OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
    return Optional.of(createdAt.toInstant());
  }

  @Override
  public void completeAuthorizationOutbox(UUID outboxId, Instant now) {
    var r =
        dsl.fetchOne(
            "SELECT operation,tenant_id,membership_id,user_id,lifecycle,request_id,state FROM identity_authorization_outbox WHERE outbox_id=? FOR UPDATE",
            outboxId);
    if (r == null) return;
    if ("COMPLETED".equals(r.get("state", String.class))) return;
    String op = r.get("operation", String.class);
    UUID tenant = r.get("tenant_id", UUID.class);
    if ("PROVISION_OWNER".equals(op)) {
      int changed =
          dsl.execute(
              "UPDATE identity_tenant SET lifecycle='ACTIVE',version=version+1,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND lifecycle='PROVISIONING'",
              ts(now),
              tenant);
      if (changed > 0)
        dsl.execute(
            "UPDATE identity_user_membership_query SET tenant_lifecycle='ACTIVE',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
            ts(now),
            tenant);
    }
    if ("APPLY_TENANT_LIFECYCLE".equals(op)) {
      String target = r.get("lifecycle", String.class);
      dsl.execute(
          "UPDATE identity_authorization_outbox SET state='COMPLETED',lease_until=NULL,completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
          ts(now),
          ts(now),
          outboxId);
      completeTenantLifecycle(
          r.get("request_id", UUID.class), tenant, r.get("user_id", UUID.class), target, now);
      return;
    }
    if ("FINALIZE_MEMBERSHIP_REMOVAL".equals(op))
      dsl.execute(
          "UPDATE identity_membership_removal_intent SET state='FINALIZED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE request_id=?",
          ts(now),
          r.get("request_id", UUID.class));
    if ("CANCEL_MEMBERSHIP_REMOVAL".equals(op))
      dsl.execute(
          "UPDATE identity_membership_removal_intent SET state='CANCELLED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE request_id=?",
          ts(now),
          r.get("request_id", UUID.class));
    dsl.execute(
        "UPDATE identity_authorization_outbox SET state='COMPLETED',lease_until=NULL,completed_at=CAST(? AS TIMESTAMP WITH TIME ZONE),updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
        ts(now),
        ts(now),
        outboxId);
  }

  @Override
  public void rescheduleAuthorizationOutbox(
      UUID outboxId, Instant now, Instant next, int attempt, boolean definitive) {
    dsl.execute(
        "UPDATE identity_authorization_outbox SET state=?,attempt_count=?,next_attempt_at=CAST(? AS TIMESTAMP WITH TIME ZONE),lease_until=NULL,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE outbox_id=?",
        definitive ? "FAILED" : "PENDING",
        attempt,
        ts(next),
        ts(now),
        outboxId);
  }

  private void completeTenantLifecycle(
      UUID requestId, UUID tenantId, UUID actorUserId, String target, Instant now) {
    var tenant =
        dsl.fetchOne(
            "SELECT lifecycle FROM identity_tenant WHERE tenant_id=? FOR UPDATE", tenantId);
    if (tenant == null) throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not found");
    String current = tenant.get("lifecycle", String.class);
    boolean valid =
        current.equals(target)
            || ("ACTIVE".equals(current) && Set.of("SUSPENDED", "DELETING").contains(target))
            || (Set.of("SUSPENDED", "PROVISIONING").contains(current) && "ACTIVE".equals(target))
            || ("DELETING".equals(current) && "DELETED".equals(target));
    if (!valid)
      throw error(TenantError.TENANT_LIFECYCLE_INVALID, "Lifecycle acknowledgement is stale");
    if (!current.equals(target)) {
      dsl.execute(
          "UPDATE identity_tenant SET lifecycle=?,deleted_at=CASE WHEN ?='DELETED' THEN CAST(? AS TIMESTAMP WITH TIME ZONE) ELSE deleted_at END,version=version+1,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
          target,
          target,
          ts(now),
          ts(now),
          tenantId);
      dsl.execute(
          "UPDATE identity_user_membership_query SET tenant_lifecycle=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=?",
          target,
          ts(now),
          tenantId);
    }
    if ("DELETING".equals(target)) {
      setTenant(tenantId);
      dsl.execute(
          "UPDATE identity_tenant_invitation SET state='REVOKED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND state='PENDING'",
          ts(now),
          tenantId);
      dsl.execute(
          "UPDATE identity_invitation_query SET state='REVOKED',updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND state='PENDING'",
          ts(now),
          tenantId);
      UUID cleanupRequest = UUID.randomUUID();
      outbox(cleanupRequest, "APPLY_TENANT_LIFECYCLE", tenantId, null, actorUserId, "DELETED", now);
      audit("IDENTITY_TENANT_DELETE_CLEANUP_QUEUED", actorUserId, now);
    } else {
      audit("IDENTITY_TENANT_" + target + "_ACKNOWLEDGED", actorUserId, now);
    }
  }

  private TenantLifecycleMutation lifecycleResult(
      UUID requestId, UUID tenantId, String requestedTarget) {
    String lifecycle = string("SELECT lifecycle FROM identity_tenant WHERE tenant_id=?", tenantId);
    if (lifecycle == null) throw error(TenantError.TENANT_NOT_SELECTABLE, "Tenant is not found");
    Boolean pending =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_authorization_outbox WHERE tenant_id=? AND operation='APPLY_TENANT_LIFECYCLE' AND state IN ('PENDING','DISPATCHING'))",
            tenantId);
    String responseTarget = "DELETING".equals(requestedTarget) ? "DELETED" : requestedTarget;
    return new TenantLifecycleMutation(
        tenantId, lifecycle, responseTarget, Boolean.TRUE.equals(pending));
  }

  private void ensureNoPendingLifecycle(UUID tenantId) {
    Boolean pending =
        bool(
            "SELECT EXISTS(SELECT 1 FROM identity_authorization_outbox WHERE tenant_id=? AND operation='APPLY_TENANT_LIFECYCLE' AND state IN ('PENDING','DISPATCHING'))",
            tenantId);
    if (Boolean.TRUE.equals(pending))
      throw error(TenantError.TENANT_LIFECYCLE_PENDING, "Tenant lifecycle command is pending");
  }

  private List<InvitationSummary> invitationSummaries(Result<? extends org.jooq.Record> rows) {
    if (rows.size() > 200)
      throw error(TenantError.SESSION_STATE_INVALID, "Invitation result limit exceeded");
    List<InvitationSummary> result = new ArrayList<>();
    for (org.jooq.Record row : rows)
      result.add(
          new InvitationSummary(
              row.get("invitation_id", UUID.class),
              row.get("tenant_id", UUID.class),
              row.get("name", String.class),
              row.get("slug", String.class),
              row.get("state", String.class),
              row.get("expires_at", OffsetDateTime.class).toInstant()));
    return List.copyOf(result);
  }

  private String effectiveInvitationState(
      UUID tenantId, UUID invitationId, org.jooq.Record invitation, Instant now) {
    String state = invitation.get("state", String.class);
    OffsetDateTime expiresAt = invitation.get("expires_at", OffsetDateTime.class);
    if ("PENDING".equals(state) && !now.isBefore(expiresAt.toInstant())) {
      updateInvitationState(tenantId, invitationId, "EXPIRED", now);
      return "EXPIRED";
    }
    return state;
  }

  private void updateInvitationState(UUID tenantId, UUID invitationId, String state, Instant now) {
    dsl.execute(
        "UPDATE identity_tenant_invitation SET state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE tenant_id=? AND invitation_id=? AND state='PENDING'",
        state,
        ts(now),
        tenantId,
        invitationId);
    dsl.execute(
        "UPDATE identity_invitation_query SET state=?,updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE invitation_id=? AND state='PENDING'",
        state,
        ts(now),
        invitationId);
  }

  private Replay replay(UUID requestId, String operation, byte[] material) {
    var r =
        dsl.fetchOne(
            "SELECT result_id,intent_fingerprint,fingerprint_key_id,fingerprint_version,created_at FROM identity_tenant_command_dedup WHERE request_id=? AND operation=?",
            requestId,
            operation);
    if (r == null) return new Replay(false, null);
    CommandDedupRecord stored =
        new CommandDedupRecord(
            requestId,
            operation,
            r.get("intent_fingerprint", byte[].class),
            r.get("fingerprint_version", String.class),
            r.get("fingerprint_key_id", String.class),
            "ACCEPTED",
            r.get("created_at", OffsetDateTime.class).toInstant());
    if (!fingerprints.matches(material, stored))
      throw error(TenantError.REQUEST_ID_CONFLICT, "Request ID conflicts with a different intent");
    return new Replay(true, r.get("result_id", UUID.class));
  }

  private void dedup(
      UUID requestId,
      String operation,
      UUID userId,
      UUID resultId,
      FingerprintDigest fp,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_tenant_command_dedup(request_id,operation,user_id,tenant_id,result_id,intent_fingerprint,fingerprint_key_id,fingerprint_version,created_at) VALUES (?,?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        requestId,
        operation,
        userId,
        "CREATE_TENANT".equals(operation) ? resultId : null,
        resultId,
        fp.value(),
        fp.keyId(),
        fp.version(),
        ts(now));
  }

  private void outbox(
      UUID requestId,
      String operation,
      UUID tenantId,
      UUID membershipId,
      UUID userId,
      String lifecycle,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_authorization_outbox(outbox_id,request_id,operation,tenant_id,membership_id,user_id,lifecycle,state,attempt_count,next_attempt_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,'PENDING',0,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT(request_id,operation) DO NOTHING",
        UUID.randomUUID(),
        requestId,
        operation,
        tenantId,
        membershipId,
        userId,
        lifecycle,
        ts(now),
        ts(now),
        ts(now));
  }

  private UUID membershipFor(UUID tenantId, UUID userId) {
    UUID id =
        uuid(
            "SELECT membership_id FROM identity_user_membership_query WHERE tenant_id=? AND user_id=?",
            tenantId,
            userId);
    if (id == null)
      throw error(TenantError.SESSION_STATE_INVALID, "Tenant replay result is incomplete");
    return id;
  }

  private void upsertMembershipQuery(
      UUID user,
      UUID membership,
      UUID tenant,
      String memberState,
      String tenantState,
      String name,
      String slug,
      Instant now) {
    dsl.execute(
        "INSERT INTO identity_user_membership_query(user_id,membership_id,tenant_id,membership_lifecycle,tenant_lifecycle,tenant_name,tenant_slug,updated_at) VALUES (?,?,?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE)) ON CONFLICT(membership_id) DO UPDATE SET membership_lifecycle=EXCLUDED.membership_lifecycle,tenant_lifecycle=EXCLUDED.tenant_lifecycle,tenant_name=EXCLUDED.tenant_name,tenant_slug=EXCLUDED.tenant_slug,updated_at=EXCLUDED.updated_at",
        user,
        membership,
        tenant,
        memberState,
        tenantState,
        name,
        slug,
        ts(now));
  }

  private void audit(String code, UUID user, Instant now) {
    dsl.execute(
        "INSERT INTO identity_security_audit(event_id,event_code,user_id,contact_id,occurred_at) VALUES (?,?,?,NULL,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        UUID.randomUUID(),
        code,
        user,
        ts(now));
  }

  private void setTenant(UUID tenant) {
    dsl.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", tenant.toString());
  }

  private String string(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : v.toString();
  }

  private UUID uuid(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : (UUID) v;
  }

  private Boolean bool(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : (Boolean) v;
  }

  private Instant instant(String sql, Object... args) {
    Object v = dsl.fetchValue(sql, args);
    return v == null ? null : ((OffsetDateTime) v).toInstant();
  }

  private static boolean isUnique(org.jooq.exception.DataAccessException e) {
    return "23505".equals(e.sqlState());
  }

  private static OffsetDateTime ts(Instant i) {
    return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
  }

  private static TenantException error(TenantError e, String m) {
    return new TenantException(e, m);
  }

  private record Replay(boolean present, UUID resultId) {}
}
