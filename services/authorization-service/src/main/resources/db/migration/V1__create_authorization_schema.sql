CREATE TABLE authorization_permission_definition (
  permission_key VARCHAR(128) PRIMARY KEY,
  scope VARCHAR(16) NOT NULL CHECK (scope IN ('TENANT','PLATFORM')),
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE','DEPRECATED','RETIRED')),
  catalog_version INTEGER NOT NULL CHECK (catalog_version > 0),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE authorization_tenant_projection (
  tenant_id UUID PRIMARY KEY,
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('PROVISIONING','ACTIVE','SUSPENDED','DELETING','DELETED')),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE authorization_membership_projection (
  tenant_id UUID NOT NULL REFERENCES authorization_tenant_projection(tenant_id),
  membership_id UUID NOT NULL,
  user_id UUID NOT NULL,
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE','SUSPENDED','REMOVED')),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id,membership_id),
  UNIQUE (tenant_id,user_id,membership_id)
);

CREATE TABLE authorization_role (
  tenant_id UUID NOT NULL REFERENCES authorization_tenant_projection(tenant_id),
  role_id UUID NOT NULL,
  name VARCHAR(320) NOT NULL,
  name_key VARCHAR(320) NOT NULL,
  description VARCHAR(2000) NOT NULL DEFAULT '',
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('SYSTEM','CUSTOM')),
  lifecycle VARCHAR(16) NOT NULL CHECK (lifecycle IN ('ACTIVE','ARCHIVED')),
  version BIGINT NOT NULL CHECK (version > 0),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id,role_id),
  UNIQUE (tenant_id,name_key)
);

CREATE TABLE authorization_role_permission (
  tenant_id UUID NOT NULL,
  role_id UUID NOT NULL,
  permission_key VARCHAR(128) NOT NULL REFERENCES authorization_permission_definition(permission_key),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id,role_id,permission_key),
  FOREIGN KEY (tenant_id,role_id) REFERENCES authorization_role(tenant_id,role_id) ON DELETE CASCADE
);

CREATE TABLE authorization_membership_role (
  tenant_id UUID NOT NULL,
  membership_id UUID NOT NULL,
  role_id UUID NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id,membership_id,role_id),
  FOREIGN KEY (tenant_id,membership_id) REFERENCES authorization_membership_projection(tenant_id,membership_id),
  FOREIGN KEY (tenant_id,role_id) REFERENCES authorization_role(tenant_id,role_id)
);

CREATE TABLE authorization_membership_permission_override (
  tenant_id UUID NOT NULL,
  membership_id UUID NOT NULL,
  permission_key VARCHAR(128) NOT NULL REFERENCES authorization_permission_definition(permission_key),
  decision VARCHAR(8) NOT NULL CHECK (decision IN ('GRANT','DENY')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (tenant_id,membership_id,permission_key),
  FOREIGN KEY (tenant_id,membership_id) REFERENCES authorization_membership_projection(tenant_id,membership_id)
);

CREATE TABLE authorization_owner_safety_guard (
  tenant_id UUID PRIMARY KEY REFERENCES authorization_tenant_projection(tenant_id),
  guard_version BIGINT NOT NULL DEFAULT 1 CHECK (guard_version > 0),
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE authorization_membership_removal_reservation (
  tenant_id UUID NOT NULL,
  membership_id UUID NOT NULL,
  request_id UUID NOT NULL,
  state VARCHAR(16) NOT NULL CHECK (state IN ('PREPARED','FINALIZED','CANCELLED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  resolved_at TIMESTAMP(6) WITH TIME ZONE,
  PRIMARY KEY (tenant_id,membership_id,request_id),
  UNIQUE (request_id),
  FOREIGN KEY (tenant_id,membership_id) REFERENCES authorization_membership_projection(tenant_id,membership_id),
  CHECK ((state='PREPARED' AND resolved_at IS NULL) OR (state IN ('FINALIZED','CANCELLED') AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX authorization_one_active_removal_reservation_idx
  ON authorization_membership_removal_reservation(tenant_id,membership_id)
  WHERE state='PREPARED';

CREATE TABLE authorization_platform_profile_assignment (
  user_id UUID PRIMARY KEY,
  profile_name VARCHAR(32) NOT NULL CHECK (profile_name='platform_admin'),
  state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED')),
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  revoked_at TIMESTAMP(6) WITH TIME ZONE
);

CREATE TABLE authorization_idempotency_record (
  request_id UUID NOT NULL,
  tenant_id UUID,
  operation VARCHAR(64) NOT NULL,
  intent_fingerprint BYTEA NOT NULL CHECK (octet_length(intent_fingerprint)=32),
  fingerprint_version VARCHAR(32) NOT NULL,
  fingerprint_key_id VARCHAR(64) NOT NULL,
  outcome_code VARCHAR(64) NOT NULL,
  outcome_reference UUID,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  PRIMARY KEY (request_id,operation)
);
CREATE INDEX authorization_idempotency_retention_idx ON authorization_idempotency_record(created_at,request_id);

CREATE TABLE authorization_audit (
  audit_id UUID PRIMARY KEY,
  event_code VARCHAR(64) NOT NULL,
  request_id UUID,
  tenant_id UUID,
  actor_user_id UUID,
  target_id UUID,
  result_code VARCHAR(64) NOT NULL,
  reason VARCHAR(2000),
  catalog_version INTEGER NOT NULL,
  occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX authorization_audit_time_idx ON authorization_audit(occurred_at,audit_id);
CREATE INDEX authorization_audit_tenant_idx ON authorization_audit(tenant_id,occurred_at,audit_id) WHERE tenant_id IS NOT NULL;

CREATE INDEX authorization_membership_user_idx ON authorization_membership_projection(user_id,tenant_id,membership_id);
CREATE INDEX authorization_role_lifecycle_idx ON authorization_role(tenant_id,lifecycle,name_key,role_id);
CREATE INDEX authorization_membership_role_role_idx ON authorization_membership_role(tenant_id,role_id,membership_id);
CREATE INDEX authorization_override_membership_idx ON authorization_membership_permission_override(tenant_id,membership_id,decision,permission_key);

ALTER TABLE authorization_membership_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_projection FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_role FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_role_permission FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_role FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_permission_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_permission_override FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_owner_safety_guard ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_owner_safety_guard FORCE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_removal_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_membership_removal_reservation FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_membership_tenant_policy ON authorization_membership_projection
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_role_tenant_policy ON authorization_role
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_role_permission_tenant_policy ON authorization_role_permission
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_membership_role_tenant_policy ON authorization_membership_role
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_override_tenant_policy ON authorization_membership_permission_override
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_owner_guard_tenant_policy ON authorization_owner_safety_guard
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
CREATE POLICY authorization_removal_reservation_tenant_policy ON authorization_membership_removal_reservation
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
