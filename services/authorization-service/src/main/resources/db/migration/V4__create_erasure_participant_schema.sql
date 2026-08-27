CREATE TABLE authorization_user_tenant_erasure_index (
    user_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    PRIMARY KEY (user_id, tenant_id, membership_id)
);

INSERT INTO authorization_user_tenant_erasure_index(user_id,tenant_id,membership_id)
SELECT user_id,tenant_id,membership_id
FROM authorization_membership_projection;

CREATE OR REPLACE FUNCTION authorization_sync_erasure_index()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR TG_OP = 'UPDATE' THEN
        DELETE FROM public.authorization_user_tenant_erasure_index
        WHERE user_id=OLD.user_id AND tenant_id=OLD.tenant_id AND membership_id=OLD.membership_id;
    END IF;
    IF TG_OP = 'INSERT' OR TG_OP = 'UPDATE' THEN
        INSERT INTO public.authorization_user_tenant_erasure_index(user_id,tenant_id,membership_id)
        VALUES (NEW.user_id,NEW.tenant_id,NEW.membership_id)
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN COALESCE(NEW,OLD);
END;
$$;

CREATE TRIGGER authorization_membership_erasure_index_trigger
AFTER INSERT OR UPDATE OF user_id,tenant_id,membership_id OR DELETE
ON authorization_membership_projection
FOR EACH ROW EXECUTE FUNCTION authorization_sync_erasure_index();

CREATE TABLE authorization_erasure_inbox (
    event_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    participant_policy_version VARCHAR(16) NOT NULL CHECK (participant_policy_version='1'),
    target_user_id UUID,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','PROCESSING','COMPLETED','EXHAUSTED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count>=0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_until TIMESTAMP(6) WITH TIME ZONE,
    redrive_requested BOOLEAN NOT NULL DEFAULT FALSE,
    last_error_class VARCHAR(64),
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT authorization_erasure_inbox_completion CHECK (
        (state IN ('PENDING','PROCESSING','EXHAUSTED') AND completed_at IS NULL)
        OR (state='COMPLETED' AND completed_at IS NOT NULL AND target_user_id IS NULL)
    ),
    CONSTRAINT authorization_erasure_inbox_retention CHECK (
        retain_until >= received_at + INTERVAL '35 days'
    )
);

CREATE INDEX authorization_erasure_inbox_due_idx
ON authorization_erasure_inbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','PROCESSING');

CREATE UNIQUE INDEX authorization_erasure_inbox_request_idx
ON authorization_erasure_inbox(erasure_request_id);

CREATE TABLE authorization_erasure_receipt_outbox (
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
    CONSTRAINT authorization_erasure_receipt_retention CHECK (
        retain_until >= occurred_at + INTERVAL '35 days'
    )
);

CREATE INDEX authorization_erasure_receipt_due_idx
ON authorization_erasure_receipt_outbox(next_attempt_at,event_id)
WHERE state IN ('PENDING','DISPATCHING');

CREATE TABLE authorization_erasure_evidence (
    evidence_id UUID PRIMARY KEY,
    erasure_request_id UUID NOT NULL,
    policy_version VARCHAR(16) NOT NULL,
    event_code VARCHAR(64) NOT NULL,
    action_categories VARCHAR(1024) NOT NULL,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    integrity_version VARCHAR(16) NOT NULL CHECK (integrity_version='v1')
);

REVOKE UPDATE, DELETE ON authorization_erasure_evidence FROM PUBLIC;
