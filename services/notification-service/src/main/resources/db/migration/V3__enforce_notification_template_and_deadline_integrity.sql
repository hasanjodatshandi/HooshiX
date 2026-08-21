ALTER TABLE notification_template_version
    ADD CONSTRAINT notification_template_version_definition_version_unique
    UNIQUE (definition_id, version_id);

ALTER TABLE notification_template_activation
    ADD CONSTRAINT notification_template_activation_active_definition_fk
    FOREIGN KEY (definition_id, active_version_id)
    REFERENCES notification_template_version(definition_id, version_id)
    NOT VALID;

ALTER TABLE notification_template_activation
    ADD CONSTRAINT notification_template_activation_previous_definition_fk
    FOREIGN KEY (definition_id, previous_version_id)
    REFERENCES notification_template_version(definition_id, version_id)
    NOT VALID;

ALTER TABLE notification_template_audit
    ADD CONSTRAINT notification_template_audit_version_definition_fk
    FOREIGN KEY (definition_id, version_id)
    REFERENCES notification_template_version(definition_id, version_id)
    NOT VALID;

ALTER TABLE notification
    ADD CONSTRAINT notification_semantic_type_valid
    CHECK (semantic_type IN (
        'REGISTRATION_VERIFICATION_CODE',
        'PASSWORD_RECOVERY_CODE',
        'MFA_VERIFICATION_CODE',
        'PASSWORD_CHANGED_NOTICE'
    )) NOT VALID,
    ADD CONSTRAINT notification_effective_deadline_after_acceptance
    CHECK (effective_delivery_deadline > accepted_at) NOT VALID,
    ADD CONSTRAINT notification_message_deadline_after_acceptance
    CHECK (message_not_after IS NULL OR message_not_after > accepted_at) NOT VALID,
    ADD CONSTRAINT notification_channel_semantic_valid
    CHECK (semantic_type <> 'PASSWORD_CHANGED_NOTICE' OR channel = 'EMAIL') NOT VALID,
    ADD CONSTRAINT notification_sensitive_expiry_after_acceptance
    CHECK (sensitive_expires_at > accepted_at) NOT VALID;

ALTER TABLE provider_receipt_evidence
    ADD CONSTRAINT provider_receipt_expiry_after_observation
    CHECK (expires_at > observed_at) NOT VALID;

ALTER TABLE notification_template_activation
    VALIDATE CONSTRAINT notification_template_activation_active_definition_fk;
ALTER TABLE notification_template_activation
    VALIDATE CONSTRAINT notification_template_activation_previous_definition_fk;
ALTER TABLE notification_template_audit
    VALIDATE CONSTRAINT notification_template_audit_version_definition_fk;
ALTER TABLE notification VALIDATE CONSTRAINT notification_semantic_type_valid;
ALTER TABLE notification VALIDATE CONSTRAINT notification_effective_deadline_after_acceptance;
ALTER TABLE notification VALIDATE CONSTRAINT notification_message_deadline_after_acceptance;
ALTER TABLE notification VALIDATE CONSTRAINT notification_channel_semantic_valid;
ALTER TABLE notification VALIDATE CONSTRAINT notification_sensitive_expiry_after_acceptance;
ALTER TABLE provider_receipt_evidence VALIDATE CONSTRAINT provider_receipt_expiry_after_observation;

CREATE INDEX provider_receipt_correlation_idx
    ON provider_receipt_evidence (provider_correlation_id, observed_at DESC)
    WHERE provider_correlation_id IS NOT NULL;

CREATE INDEX notification_result_outbox_pending_idx
    ON notification_result_outbox (next_attempt_at, outbox_id)
    WHERE completed_at IS NULL;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_runtime') THEN
        REVOKE ALL PRIVILEGES ON TABLE
            notification_template_definition,
            notification_template_version,
            notification_template_activation,
            notification_template_audit
        FROM notification_runtime;

        GRANT SELECT ON TABLE
            notification_template_definition,
            notification_template_version,
            notification_template_activation,
            notification_template_audit
        TO notification_runtime;
    END IF;
END
$$;
