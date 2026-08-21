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
    implements TenantStore, TenantContextValidationPort, AuthenticationTenantSelectionPort {
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
  public void completeAuthorizationOutbox(UUID outboxId, Instant now) {
    var r =
        dsl.fetchOne(
            "SELECT operation,tenant_id,membership_id,request_id FROM identity_authorization_outbox WHERE outbox_id=? FOR UPDATE",
            outboxId);
    if (r == null) return;
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
