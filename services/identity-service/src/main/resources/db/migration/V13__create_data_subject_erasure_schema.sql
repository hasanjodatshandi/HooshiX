ALTER TABLE identity_refresh_family
    DROP CONSTRAINT identity_refresh_family_revocation_reason_check;

ALTER TABLE identity_refresh_family
    ADD CONSTRAINT identity_refresh_family_revocation_reason_check
        CHECK (revocation_reason IS NULL OR revocation_reason IN (
            'ACTIVE_FAMILY_LIMIT',
            'LOGOUT_CURRENT',
            'LOGOUT_ALL',
            'REFRESH_REUSE',
            'EXPIRED',
            'USER_INACTIVE',
            'PASSWORD_CHANGED',
            'MFA_CHANGED',
            'EXTERNAL_IDENTITY_CHANGED',
            'ERASURE_REQUESTED'
        ));

CREATE TABLE identity_erasure_request (
    erasure_request_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'REQUESTED', 'IN_PROGRESS', 'COMPLETED',
        'BLOCKED_BY_LEGAL_HOLD', 'FAILED_RETRYABLE'
    )),
    participant_policy_version VARCHAR(16) NOT NULL CHECK (participant_policy_version = '1'),
    accepted_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_erasure_request_completion_pair CHECK (
        (state = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (state <> 'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE UNIQUE INDEX identity_erasure_request_one_open_user_idx
    ON identity_erasure_request(user_id)
    WHERE state <> 'COMPLETED';

CREATE TABLE identity_erasure_participant (
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    participant VARCHAR(32) NOT NULL CHECK (participant IN (
        'IDENTITY_SERVICE', 'AUTHORIZATION_SERVICE', 'NOTIFICATION_SERVICE', 'WEB_BFF'
    )),
    state VARCHAR(32) NOT NULL CHECK (state IN (
        'PENDING', 'IN_PROGRESS', 'COMPLETED',
        'BLOCKED_BY_LEGAL_HOLD', 'FAILED_RETRYABLE'
    )),
    receipt_event_id UUID,
    started_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (erasure_request_id, participant),
    CONSTRAINT identity_erasure_participant_completion_pair CHECK (
        (state = 'COMPLETED' AND receipt_event_id IS NOT NULL AND completed_at IS NOT NULL)
        OR (state <> 'COMPLETED' AND completed_at IS NULL)
    )
);

CREATE TABLE identity_erasure_notification_target (
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    notification_id UUID NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (erasure_request_id, notification_id)
);

CREATE TABLE identity_erasure_event_outbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    event_type VARCHAR(24) NOT NULL CHECK (event_type IN ('COMMAND', 'RECEIPT')),
    participant_policy_version VARCHAR(16) NOT NULL CHECK (participant_policy_version = '1'),
    participant VARCHAR(32),
    outcome VARCHAR(32),
    action_categories VARCHAR(1024),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'DISPATCHING', 'PUBLISHED', 'EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error_class VARCHAR(64),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_erasure_outbox_retention CHECK (
        retain_until >= occurred_at + INTERVAL '35 days'
    ),
    CONSTRAINT identity_erasure_outbox_event_shape CHECK (
        (event_type='COMMAND' AND participant IS NULL AND outcome IS NULL AND action_categories IS NULL)
        OR
        (event_type='RECEIPT' AND participant IS NOT NULL AND outcome IS NOT NULL AND action_categories IS NOT NULL)
    )
);

CREATE INDEX identity_erasure_event_outbox_due_idx
    ON identity_erasure_event_outbox(next_attempt_at, event_id)
    WHERE state IN ('PENDING', 'DISPATCHING');

CREATE TABLE identity_erasure_receipt_inbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    participant VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    participant_policy_version VARCHAR(16) NOT NULL,
    action_categories VARCHAR(1024) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','PROCESSING','COMPLETED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    last_error_class VARCHAR(64),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_erasure_receipt_inbox_retention CHECK (
        retain_until >= received_at + INTERVAL '35 days'
    )
);

CREATE INDEX identity_erasure_receipt_inbox_due_idx
ON identity_erasure_receipt_inbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','PROCESSING');

CREATE TABLE identity_erasure_command_inbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
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
    CONSTRAINT identity_erasure_command_inbox_completion CHECK (
        (state IN ('PENDING','PROCESSING','EXHAUSTED') AND completed_at IS NULL)
        OR (state='COMPLETED' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT identity_erasure_command_inbox_retention CHECK (
        retain_until >= received_at + INTERVAL '35 days'
    )
);

CREATE INDEX identity_erasure_command_inbox_due_idx
ON identity_erasure_command_inbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','PROCESSING');

CREATE UNIQUE INDEX identity_erasure_command_inbox_request_idx
ON identity_erasure_command_inbox(erasure_request_id);

CREATE TABLE identity_invitation_user_erasure_index (
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    invitation_id UUID NOT NULL,
    relation VARCHAR(16) NOT NULL CHECK (relation IN ('TARGET','INVITER')),
    PRIMARY KEY(user_id,tenant_id,invitation_id,relation)
);

INSERT INTO identity_invitation_user_erasure_index(user_id,tenant_id,invitation_id,relation)
SELECT target_user_id,tenant_id,invitation_id,'TARGET' FROM identity_tenant_invitation
UNION ALL
SELECT invited_by_user_id,tenant_id,invitation_id,'INVITER' FROM identity_tenant_invitation;

CREATE OR REPLACE FUNCTION identity_sync_invitation_erasure_index()
RETURNS trigger
LANGUAGE plpgsql
SET search_path=pg_catalog,public
AS $$
BEGIN
    IF TG_OP='DELETE' OR TG_OP='UPDATE' THEN
        DELETE FROM public.identity_invitation_user_erasure_index
        WHERE tenant_id=OLD.tenant_id AND invitation_id=OLD.invitation_id;
    END IF;
    IF TG_OP='INSERT' OR TG_OP='UPDATE' THEN
        INSERT INTO public.identity_invitation_user_erasure_index(user_id,tenant_id,invitation_id,relation)
        VALUES (NEW.target_user_id,NEW.tenant_id,NEW.invitation_id,'TARGET'),
               (NEW.invited_by_user_id,NEW.tenant_id,NEW.invitation_id,'INVITER')
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN COALESCE(NEW,OLD);
END;
$$;

CREATE TRIGGER identity_invitation_erasure_index_trigger
AFTER INSERT OR UPDATE OF target_user_id,invited_by_user_id,tenant_id,invitation_id OR DELETE
ON identity_tenant_invitation
FOR EACH ROW EXECUTE FUNCTION identity_sync_invitation_erasure_index();

CREATE TABLE identity_legal_hold (
    hold_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    authority_reference VARCHAR(128) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES identity_user(user_id),
    policy_version VARCHAR(16) NOT NULL CHECK (policy_version = '1'),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP(6) WITH TIME ZONE,
    released_by_user_id UUID REFERENCES identity_user(user_id),
    CONSTRAINT identity_legal_hold_release_pair CHECK (
        (status = 'ACTIVE' AND released_at IS NULL AND released_by_user_id IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND released_by_user_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX identity_legal_hold_one_active_request_idx
    ON identity_legal_hold(erasure_request_id)
    WHERE status = 'ACTIVE';

CREATE TABLE identity_erasure_evidence (
    evidence_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL
        REFERENCES identity_erasure_request(erasure_request_id) ON DELETE RESTRICT,
    service VARCHAR(32) NOT NULL,
    policy_version VARCHAR(16) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    action_categories VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    integrity_version VARCHAR(16) NOT NULL CHECK (integrity_version = 'v1')
);

CREATE INDEX identity_erasure_evidence_request_time_idx
    ON identity_erasure_evidence(erasure_request_id, occurred_at, evidence_id);

REVOKE UPDATE, DELETE ON identity_erasure_evidence FROM PUBLIC;
