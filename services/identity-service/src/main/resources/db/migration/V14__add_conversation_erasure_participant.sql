ALTER TABLE identity_erasure_request
    DROP CONSTRAINT identity_erasure_request_participant_policy_version_check;

ALTER TABLE identity_erasure_request
    ADD CONSTRAINT identity_erasure_request_participant_policy_version_check
        CHECK (participant_policy_version IN ('1', '2'));

ALTER TABLE identity_erasure_participant
    DROP CONSTRAINT identity_erasure_participant_participant_check;

ALTER TABLE identity_erasure_participant
    ADD CONSTRAINT identity_erasure_participant_participant_check
        CHECK (participant IN (
            'IDENTITY_SERVICE',
            'AUTHORIZATION_SERVICE',
            'NOTIFICATION_SERVICE',
            'WEB_BFF',
            'CONVERSATION_SERVICE'
        ));

ALTER TABLE identity_erasure_event_outbox
    DROP CONSTRAINT identity_erasure_event_outbox_participant_policy_version_check;

ALTER TABLE identity_erasure_event_outbox
    ADD CONSTRAINT identity_erasure_event_outbox_participant_policy_version_check
        CHECK (participant_policy_version IN ('1', '2'));

ALTER TABLE identity_erasure_command_inbox
    DROP CONSTRAINT identity_erasure_command_inbox_participant_policy_version_check;

ALTER TABLE identity_erasure_command_inbox
    ADD CONSTRAINT identity_erasure_command_inbox_participant_policy_version_check
        CHECK (participant_policy_version IN ('1', '2'));

CREATE TABLE identity_tenant_lifecycle_event_outbox (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES identity_tenant(tenant_id) ON DELETE RESTRICT,
    lifecycle_version BIGINT NOT NULL CHECK (lifecycle_version > 0),
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN (
        'PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DELETING', 'DELETED'
    )),
    state VARCHAR(16) NOT NULL CHECK (state IN (
        'PENDING', 'DISPATCHING', 'PUBLISHED', 'EXHAUSTED'
    )),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    last_error_class VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, lifecycle_version)
);

CREATE INDEX identity_tenant_lifecycle_event_outbox_due_idx
    ON identity_tenant_lifecycle_event_outbox(next_attempt_at, event_id)
    WHERE state IN ('PENDING', 'DISPATCHING');

INSERT INTO identity_tenant_lifecycle_event_outbox(
    event_id, tenant_id, lifecycle_version, lifecycle_state, state,
    attempt_count, next_attempt_at, occurred_at, updated_at)
SELECT gen_random_uuid(), tenant_id, version, lifecycle, 'PENDING',
       0, updated_at, updated_at, updated_at
FROM identity_tenant;
