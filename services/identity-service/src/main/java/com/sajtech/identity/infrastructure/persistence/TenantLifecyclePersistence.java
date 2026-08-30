package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.*;
import java.util.*;
import org.jooq.*;

final class TenantLifecyclePersistence extends TenantPersistenceSupport {
  private final TenantContextValidationPort tenantContexts;

  TenantLifecyclePersistence(
      DSLContext dsl,
      IntentFingerprintPort fingerprints,
      TenantContextValidationPort tenantContexts) {
    super(dsl, fingerprints);
    this.tenantContexts = Objects.requireNonNull(tenantContexts);
  }

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
    if (!tenantContexts.isSelectable(actorUserId, selectedTenantId, selectedMembershipId))
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
}
