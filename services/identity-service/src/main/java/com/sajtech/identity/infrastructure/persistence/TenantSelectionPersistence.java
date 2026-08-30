package com.sajtech.identity.infrastructure.persistence;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.out.IntentFingerprintPort;
import com.sajtech.identity.application.tenant.*;
import com.sajtech.identity.application.tenant.model.*;
import java.time.*;
import java.util.*;
import org.jooq.*;

class TenantSelectionPersistence extends TenantPersistenceSupport {
  TenantSelectionPersistence(DSLContext dsl, IntentFingerprintPort fingerprints) {
    super(dsl, fingerprints);
  }

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

  public UUID lastSelectedMembership(UUID userId) {
    return uuid(
        "SELECT last_selected_membership_id FROM identity_user_tenant_preference WHERE user_id=?",
        userId);
  }

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
}
