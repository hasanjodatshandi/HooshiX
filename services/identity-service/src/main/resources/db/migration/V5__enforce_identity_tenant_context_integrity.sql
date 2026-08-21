CREATE UNIQUE INDEX identity_tenant_membership_context_identity_idx
    ON identity_tenant_membership (tenant_id, membership_id, user_id);

ALTER TABLE identity_refresh_family
    ADD CONSTRAINT identity_refresh_family_selected_membership_fk
    FOREIGN KEY (selected_tenant_id, selected_membership_id, user_id)
    REFERENCES identity_tenant_membership (tenant_id, membership_id, user_id)
    NOT VALID;

ALTER TABLE identity_refresh_family
    VALIDATE CONSTRAINT identity_refresh_family_selected_membership_fk;

ALTER TABLE identity_tenant_invitation
    ADD CONSTRAINT identity_tenant_invitation_accepted_membership_fk
    FOREIGN KEY (tenant_id, accepted_membership_id, target_user_id)
    REFERENCES identity_tenant_membership (tenant_id, membership_id, user_id)
    NOT VALID;

ALTER TABLE identity_tenant_invitation
    VALIDATE CONSTRAINT identity_tenant_invitation_accepted_membership_fk;
