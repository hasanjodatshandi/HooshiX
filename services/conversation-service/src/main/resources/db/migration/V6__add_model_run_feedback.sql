ALTER TABLE conversation_model_run
    ADD CONSTRAINT uq_conversation_model_run_feedback_scope
    UNIQUE (run_id, tenant_id, conversation_id);

CREATE TABLE conversation_run_feedback (
    tenant_id UUID NOT NULL,
    conversation_id UUID NOT NULL,
    run_id UUID NOT NULL,
    requester_membership_id UUID NOT NULL,
    request_id UUID NOT NULL,
    value VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (tenant_id, requester_membership_id, request_id),
    UNIQUE (tenant_id, requester_membership_id, run_id),
    CONSTRAINT fk_conversation_run_feedback_conversation
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversation(conversation_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_conversation_run_feedback_run
        FOREIGN KEY (run_id, tenant_id, conversation_id)
        REFERENCES conversation_model_run(run_id, tenant_id, conversation_id) ON DELETE CASCADE,
    CONSTRAINT ck_conversation_run_feedback_request_uuid_v4 CHECK (
        (get_byte(uuid_send(request_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(request_id), 8) & 192) = 128
    ),
    CONSTRAINT ck_conversation_run_feedback_value CHECK (
        value IN ('HELPFUL','NOT_HELPFUL','UNSAFE','FACTUALLY_WRONG')
    )
);

CREATE INDEX ix_conversation_run_feedback_aggregate
    ON conversation_run_feedback(tenant_id, value, created_at);

ALTER TABLE conversation_run_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_run_feedback FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_run_feedback_tenant_isolation ON conversation_run_feedback
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
