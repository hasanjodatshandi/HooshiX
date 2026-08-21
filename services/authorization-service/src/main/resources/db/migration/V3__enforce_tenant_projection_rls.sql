ALTER TABLE authorization_tenant_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE authorization_tenant_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY authorization_tenant_projection_tenant_policy ON authorization_tenant_projection
  USING (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid)
  WITH CHECK (tenant_id = NULLIF(current_setting('app.current_tenant_id',true),'')::uuid);
