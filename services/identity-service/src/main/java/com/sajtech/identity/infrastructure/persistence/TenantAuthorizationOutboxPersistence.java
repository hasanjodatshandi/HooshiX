package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.*;
import java.util.*;
import org.jooq.*;

final class TenantAuthorizationOutboxPersistence extends TenantPersistenceSupport {
  TenantAuthorizationOutboxPersistence(DSLContext dsl, IntentFingerprintPort fingerprints) {
    super(dsl, fingerprints);
  }

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
}
