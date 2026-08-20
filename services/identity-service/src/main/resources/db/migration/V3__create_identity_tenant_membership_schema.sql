CREATE TABLE identity_tenant (
  tenant_id UUID PRIMARY KEY,
  name VARCHAR(480) NOT NULL,
  slug VARCHAR(63) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$'),
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('PROVISIONING','ACTIVE','SUSPENDED','DELETING','DELETED')),
  creator_user_id UUID NOT NULL REFERENCES identity_user(user_id),
  version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  deleted_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX identity_tenant_creator_idx ON identity_tenant(creator_user_id,created_at,tenant_id);

CREATE TABLE identity_tenant_membership (
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  membership_id UUID NOT NULL,
  user_id UUID NOT NULL REFERENCES identity_user(user_id),
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE','SUSPENDED','REMOVED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  removed_at TIMESTAMP(6) WITH TIME ZONE,
  PRIMARY KEY(tenant_id,membership_id),
  UNIQUE(membership_id),
  UNIQUE(tenant_id,user_id)
);
CREATE INDEX identity_tenant_membership_user_idx ON identity_tenant_membership(user_id,tenant_id,membership_id);

CREATE TABLE identity_tenant_invitation (
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  invitation_id UUID NOT NULL,
  target_user_id UUID NOT NULL REFERENCES identity_user(user_id),
  target_contact_id UUID NOT NULL REFERENCES identity_contact(contact_id),
  invited_by_user_id UUID NOT NULL REFERENCES identity_user(user_id),
  state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','ACCEPTED','DECLINED','EXPIRED','REVOKED')),
  expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  accepted_membership_id UUID,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY(tenant_id,invitation_id),
  UNIQUE(invitation_id)
);
CREATE UNIQUE INDEX identity_tenant_invitation_one_pending_idx ON identity_tenant_invitation(tenant_id,target_user_id) WHERE state='PENDING';
CREATE INDEX identity_tenant_invitation_target_idx ON identity_tenant_invitation(target_user_id,state,expires_at,invitation_id);

CREATE TABLE identity_invitation_query (
  invitation_id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  target_user_id UUID NOT NULL REFERENCES identity_user(user_id),
  state VARCHAR(16) NOT NULL,
  expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX identity_invitation_query_target_idx ON identity_invitation_query(target_user_id,state,expires_at,invitation_id);
CREATE TABLE identity_user_membership_query (
  user_id UUID NOT NULL REFERENCES identity_user(user_id),
  membership_id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  membership_lifecycle VARCHAR(16) NOT NULL,
  tenant_lifecycle VARCHAR(16) NOT NULL,
  tenant_name VARCHAR(480) NOT NULL,
  tenant_slug VARCHAR(63) NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX identity_user_membership_query_user_idx ON identity_user_membership_query(user_id,membership_lifecycle,tenant_lifecycle,tenant_id,membership_id);

CREATE TABLE identity_user_tenant_preference (
  user_id UUID PRIMARY KEY REFERENCES identity_user(user_id),
  last_selected_membership_id UUID,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE identity_authorization_outbox (
  outbox_id UUID PRIMARY KEY,
  request_id UUID NOT NULL,
  operation VARCHAR(32) NOT NULL CHECK (operation IN ('PROVISION_OWNER','PROVISION_MEMBER','APPLY_TENANT_LIFECYCLE','FINALIZE_MEMBERSHIP_REMOVAL','CANCEL_MEMBERSHIP_REMOVAL')),
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  membership_id UUID,
  user_id UUID,
  lifecycle VARCHAR(16),
  state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','DISPATCHING','COMPLETED','FAILED')),
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  lease_until TIMESTAMP(6) WITH TIME ZONE,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP(6) WITH TIME ZONE,
  UNIQUE(request_id,operation)
);
CREATE INDEX identity_authorization_outbox_due_idx ON identity_authorization_outbox(state,next_attempt_at,outbox_id) WHERE state IN ('PENDING','DISPATCHING');

CREATE TABLE identity_membership_removal_intent (
  request_id UUID PRIMARY KEY,
  tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id),
  membership_id UUID NOT NULL,
  requested_by_user_id UUID NOT NULL REFERENCES identity_user(user_id),
  state VARCHAR(24) NOT NULL CHECK (state IN ('PREPARING','PREPARED','LOCAL_COMMITTED','CANCEL_PENDING','FINALIZED','CANCELLED','FAILED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  FOREIGN KEY(tenant_id,membership_id) REFERENCES identity_tenant_membership(tenant_id,membership_id)
);

CREATE TABLE identity_tenant_command_dedup (
  request_id UUID NOT NULL,
  operation VARCHAR(32) NOT NULL,
  user_id UUID NOT NULL REFERENCES identity_user(user_id),
  tenant_id UUID,
  result_id UUID,
  intent_fingerprint BYTEA NOT NULL CHECK (octet_length(intent_fingerprint)=32),
  fingerprint_key_id VARCHAR(64) NOT NULL,
  fingerprint_version VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY(request_id,operation)
);
CREATE INDEX identity_tenant_command_dedup_retention_idx ON identity_tenant_command_dedup(created_at,request_id);

ALTER TABLE identity_refresh_family ADD COLUMN selected_tenant_id UUID REFERENCES identity_tenant(tenant_id);
ALTER TABLE identity_refresh_family ADD COLUMN selected_membership_id UUID;
ALTER TABLE identity_refresh_family DROP CONSTRAINT identity_refresh_family_session_mode_check;
ALTER TABLE identity_refresh_family ADD CONSTRAINT identity_refresh_family_session_mode_check CHECK (session_mode IN ('AUTHENTICATED_ONBOARDING','TENANT_AUTHENTICATED'));
ALTER TABLE identity_refresh_family ADD CONSTRAINT identity_refresh_family_tenant_context_pair CHECK ((selected_tenant_id IS NULL AND selected_membership_id IS NULL AND session_mode='AUTHENTICATED_ONBOARDING') OR (selected_tenant_id IS NOT NULL AND selected_membership_id IS NOT NULL AND session_mode='TENANT_AUTHENTICATED'));

ALTER TABLE identity_tenant_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_tenant_membership FORCE ROW LEVEL SECURITY;
ALTER TABLE identity_tenant_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_tenant_invitation FORCE ROW LEVEL SECURITY;
CREATE POLICY identity_membership_tenant_policy ON identity_tenant_membership USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY identity_invitation_tenant_policy ON identity_tenant_invitation USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
