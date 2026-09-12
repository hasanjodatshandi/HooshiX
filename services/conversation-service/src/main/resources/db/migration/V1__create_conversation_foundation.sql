CREATE TABLE conversation (
    conversation_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    owner_membership_id uuid NOT NULL,
    title_key_id varchar(64) NOT NULL,
    title_nonce bytea NOT NULL,
    title_ciphertext bytea NOT NULL,
    lifecycle varchar(16) NOT NULL,
    aggregate_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    CONSTRAINT uq_conversation_tenant_id UNIQUE (conversation_id, tenant_id),
    CONSTRAINT ck_conversation_title_key_id CHECK (title_key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_conversation_title_nonce CHECK (octet_length(title_nonce) = 12),
    CONSTRAINT ck_conversation_title_ciphertext CHECK (octet_length(title_ciphertext) >= 16),
    CONSTRAINT ck_conversation_lifecycle CHECK (lifecycle IN ('ACTIVE', 'ARCHIVED', 'DELETED')),
    CONSTRAINT ck_conversation_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_conversation_activity_order CHECK (last_activity_at >= created_at)
);

CREATE INDEX ix_conversation_tenant_owner_activity
    ON conversation (tenant_id, owner_membership_id, last_activity_at DESC, conversation_id);

CREATE TABLE conversation_message (
    message_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    author_membership_id uuid,
    role varchar(16) NOT NULL,
    content_key_id varchar(64) NOT NULL,
    content_nonce bytea NOT NULL,
    content_ciphertext bytea NOT NULL,
    ordinal bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT fk_conversation_message_conversation
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversation (conversation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_conversation_message_ordinal UNIQUE (conversation_id, ordinal),
    CONSTRAINT ck_conversation_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_conversation_message_author CHECK (
        (role = 'USER' AND author_membership_id IS NOT NULL)
        OR (role = 'ASSISTANT' AND author_membership_id IS NULL)
    ),
    CONSTRAINT ck_conversation_message_key_id CHECK (content_key_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_conversation_message_nonce CHECK (octet_length(content_nonce) = 12),
    CONSTRAINT ck_conversation_message_ciphertext CHECK (octet_length(content_ciphertext) >= 16),
    CONSTRAINT ck_conversation_message_ordinal CHECK (ordinal >= 1)
);

CREATE INDEX ix_conversation_message_history
    ON conversation_message (tenant_id, conversation_id, ordinal DESC);

CREATE TABLE conversation_model_run (
    run_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    requester_membership_id uuid NOT NULL,
    request_id uuid NOT NULL,
    state varchar(24) NOT NULL,
    model_alias varchar(64) NOT NULL,
    prompt_version varchar(64) NOT NULL,
    price_version varchar(64) NOT NULL,
    reserved_cost_microunits bigint NOT NULL,
    actual_cost_microunits bigint,
    input_tokens integer,
    output_tokens integer,
    failure_category varchar(32),
    created_at timestamptz NOT NULL,
    claimed_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT fk_conversation_model_run_conversation
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversation (conversation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_conversation_model_run_request
        UNIQUE (tenant_id, requester_membership_id, request_id),
    CONSTRAINT ck_conversation_model_run_request_uuid_v4 CHECK (
        (get_byte(uuid_send(request_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(request_id), 8) & 192) = 128
    ),
    CONSTRAINT ck_conversation_model_run_state CHECK (
        state IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED', 'OUTCOME_UNKNOWN')
    ),
    CONSTRAINT ck_conversation_model_run_model_alias CHECK (model_alias ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    CONSTRAINT ck_conversation_model_run_prompt_version CHECK (prompt_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_conversation_model_run_price_version CHECK (price_version ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT ck_conversation_model_run_reserved_cost CHECK (reserved_cost_microunits >= 0),
    CONSTRAINT ck_conversation_model_run_actual_cost CHECK (
        actual_cost_microunits IS NULL
        OR (actual_cost_microunits >= 0 AND actual_cost_microunits <= reserved_cost_microunits)
    ),
    CONSTRAINT ck_conversation_model_run_input_tokens CHECK (input_tokens IS NULL OR input_tokens >= 0),
    CONSTRAINT ck_conversation_model_run_output_tokens CHECK (output_tokens IS NULL OR output_tokens >= 0),
    CONSTRAINT ck_conversation_model_run_terminal_time CHECK (
        (state IN ('QUEUED', 'RUNNING') AND completed_at IS NULL)
        OR (state IN ('SUCCEEDED', 'FAILED', 'CANCELED', 'OUTCOME_UNKNOWN') AND completed_at IS NOT NULL)
    )
);

CREATE INDEX ix_conversation_model_run_claim
    ON conversation_model_run (created_at, run_id)
    WHERE state = 'QUEUED';

CREATE INDEX ix_conversation_model_run_history
    ON conversation_model_run (tenant_id, conversation_id, created_at DESC, run_id);

ALTER TABLE conversation ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_message FORCE ROW LEVEL SECURITY;
ALTER TABLE conversation_model_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_model_run FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_tenant_isolation ON conversation
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY conversation_message_tenant_isolation ON conversation_message
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY conversation_model_run_tenant_isolation ON conversation_model_run
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
