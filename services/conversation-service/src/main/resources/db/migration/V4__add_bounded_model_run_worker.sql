ALTER TABLE conversation_message
    ADD COLUMN model_run_id uuid,
    ADD CONSTRAINT fk_conversation_message_model_run
        FOREIGN KEY (model_run_id, conversation_id, tenant_id)
        REFERENCES conversation_model_run (run_id, conversation_id, tenant_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT uq_conversation_message_model_run UNIQUE (model_run_id),
    ADD CONSTRAINT ck_conversation_message_model_run_role CHECK (
        (role = 'USER' AND model_run_id IS NULL)
        OR (role = 'ASSISTANT' AND model_run_id IS NOT NULL)
    );

-- Global worker control-plane metadata. It contains only technical identifiers and lease timing,
-- never title, prompt, message, output, user contact, credential, or provider payload data.
CREATE TABLE conversation_model_run_queue (
    run_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    available_at timestamptz NOT NULL,
    claimed_until timestamptz,
    CONSTRAINT fk_conversation_model_run_queue_run
        FOREIGN KEY (run_id, conversation_id, tenant_id)
        REFERENCES conversation_model_run (run_id, conversation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_conversation_model_run_queue_lease CHECK (
        claimed_until IS NULL OR claimed_until >= available_at
    )
);

CREATE INDEX ix_conversation_model_run_queue_claim
    ON conversation_model_run_queue (available_at, run_id)
    WHERE claimed_until IS NULL;

CREATE INDEX ix_conversation_model_run_queue_tenant_lease
    ON conversation_model_run_queue (tenant_id, claimed_until)
    WHERE claimed_until IS NOT NULL;

INSERT INTO conversation_model_run_queue (run_id, tenant_id, conversation_id, available_at)
SELECT run_id, tenant_id, conversation_id, created_at
FROM conversation_model_run
WHERE state = 'QUEUED';
