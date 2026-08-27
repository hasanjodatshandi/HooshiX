CREATE TABLE notification_erasure_inbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    participant_policy_version VARCHAR(16) NOT NULL CHECK (participant_policy_version='1'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','PROCESSING','COMPLETED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    redrive_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_class VARCHAR(64),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT notification_erasure_inbox_completion CHECK (
        (state IN ('PENDING','PROCESSING','EXHAUSTED') AND completed_at IS NULL)
        OR (state='COMPLETED' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT notification_erasure_inbox_retention CHECK (
        retain_until >= received_at + INTERVAL '35 days'
    )
);

CREATE INDEX notification_erasure_inbox_due_idx
ON notification_erasure_inbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','PROCESSING');

CREATE UNIQUE INDEX notification_erasure_inbox_request_idx
ON notification_erasure_inbox(erasure_request_id);

CREATE TABLE notification_erasure_receipt_outbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    participant_policy_version VARCHAR(16) NOT NULL CHECK (participant_policy_version='1'),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('COMPLETED','FAILED_RETRYABLE')),
    action_categories VARCHAR(1024) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','DISPATCHING','PUBLISHED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error_class VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT notification_erasure_receipt_retention CHECK (
        retain_until >= occurred_at + INTERVAL '35 days'
    )
);

CREATE INDEX notification_erasure_receipt_due_idx
ON notification_erasure_receipt_outbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','DISPATCHING');

CREATE TABLE notification_erasure_evidence (
    evidence_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    policy_version VARCHAR(16) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    action_categories VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    integrity_version VARCHAR(16) NOT NULL CHECK (integrity_version='v1')
);

REVOKE UPDATE, DELETE ON notification_erasure_evidence FROM PUBLIC;
