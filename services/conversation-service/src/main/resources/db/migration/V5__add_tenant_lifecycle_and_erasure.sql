ALTER TABLE conversation ADD COLUMN owner_user_id UUID;

ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_owner_user_erasure CHECK (
        lifecycle = 'DELETED' OR owner_user_id IS NOT NULL
    ) NOT VALID;

CREATE TABLE conversation_subject_index (
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    PRIMARY KEY (user_id, tenant_id, conversation_id),
    UNIQUE (conversation_id, tenant_id),
    CONSTRAINT fk_conversation_subject_index_conversation
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversation(conversation_id, tenant_id)
        ON DELETE CASCADE
);

CREATE TABLE conversation_tenant_lifecycle (
    tenant_id UUID PRIMARY KEY,
    lifecycle_version BIGINT NOT NULL CHECK (lifecycle_version > 0),
    lifecycle_state VARCHAR(16) NOT NULL CHECK (lifecycle_state IN (
        'PROVISIONING', 'ACTIVE', 'SUSPENDED', 'DELETING', 'DELETED'
    )),
    ordered BOOLEAN NOT NULL,
    purge_started_at TIMESTAMP(6) WITH TIME ZONE,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE conversation_tenant_lifecycle_inbox (
    event_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    lifecycle_version BIGINT NOT NULL CHECK (lifecycle_version > 0),
    lifecycle_state VARCHAR(16) NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN (
        'APPLIED', 'DUPLICATE', 'GAP', 'CONFLICT', 'RESTORE_FORBIDDEN'
    )),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (tenant_id, lifecycle_version, event_id)
);

CREATE INDEX conversation_tenant_lifecycle_inbox_reconcile_idx
    ON conversation_tenant_lifecycle_inbox(received_at, tenant_id)
    WHERE outcome IN ('GAP', 'CONFLICT', 'RESTORE_FORBIDDEN');

CREATE TABLE conversation_erasure_inbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL UNIQUE,
    participant_policy_version VARCHAR(16) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','PROCESSING','COMPLETED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    redrive_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_class VARCHAR(64),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT conversation_erasure_inbox_completion CHECK (
        (state IN ('PENDING','PROCESSING','EXHAUSTED') AND completed_at IS NULL)
        OR (state='COMPLETED' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT conversation_erasure_inbox_retention CHECK (
        retain_until >= received_at + INTERVAL '35 days'
    )
);

CREATE INDEX conversation_erasure_inbox_due_idx
    ON conversation_erasure_inbox(next_attempt_at,event_id)
    WHERE state IN ('PENDING','PROCESSING');

CREATE TABLE conversation_erasure_receipt_outbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    participant_policy_version VARCHAR(16) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome='COMPLETED'),
    action_categories VARCHAR(1024) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','DISPATCHING','PUBLISHED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error_class VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT conversation_erasure_receipt_retention CHECK (
        retain_until >= occurred_at + INTERVAL '35 days'
    )
);

CREATE INDEX conversation_erasure_receipt_due_idx
    ON conversation_erasure_receipt_outbox(next_attempt_at,event_id)
    WHERE state IN ('PENDING','DISPATCHING');

CREATE TABLE conversation_erasure_evidence (
    evidence_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    policy_version VARCHAR(16) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    action_categories VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    integrity_version VARCHAR(16) NOT NULL
);

REVOKE UPDATE, DELETE ON conversation_erasure_evidence FROM PUBLIC;
