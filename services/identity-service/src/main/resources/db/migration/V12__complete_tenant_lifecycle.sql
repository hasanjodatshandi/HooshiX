ALTER TABLE identity_tenant
  ADD COLUMN purge_started_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE identity_tenant_invitation
  ADD COLUMN reissued_from_invitation_id UUID REFERENCES identity_tenant_invitation(invitation_id);

CREATE INDEX identity_invitation_query_pending_expiry_idx
  ON identity_invitation_query(expires_at, invitation_id)
  WHERE state = 'PENDING';

CREATE UNIQUE INDEX identity_authorization_outbox_one_tenant_lifecycle_pending_idx
  ON identity_authorization_outbox(tenant_id)
  WHERE operation = 'APPLY_TENANT_LIFECYCLE'
    AND state IN ('PENDING', 'DISPATCHING');
