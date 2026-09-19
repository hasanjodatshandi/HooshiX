ALTER TABLE conversation_model_run
    ADD COLUMN user_message_id uuid,
    ADD COLUMN cancellation_requested boolean NOT NULL DEFAULT false,
    ADD COLUMN cancel_request_id uuid;

ALTER TABLE conversation_model_run
    ALTER COLUMN actual_cost_microunits SET DEFAULT 0;

UPDATE conversation_model_run
SET actual_cost_microunits = 0
WHERE actual_cost_microunits IS NULL;

ALTER TABLE conversation_model_run
    ALTER COLUMN actual_cost_microunits SET NOT NULL,
    ADD CONSTRAINT fk_conversation_model_run_user_message
        FOREIGN KEY (user_message_id) REFERENCES conversation_message (message_id),
    ADD CONSTRAINT ck_conversation_model_run_cancel_request_uuid_v4 CHECK (
        cancel_request_id IS NULL
        OR (
            (get_byte(uuid_send(cancel_request_id), 6) >> 4) = 4
            AND (get_byte(uuid_send(cancel_request_id), 8) & 192) = 128
        )
    );

CREATE UNIQUE INDEX uq_conversation_model_run_cancel_request
    ON conversation_model_run (tenant_id, requester_membership_id, cancel_request_id)
    WHERE cancel_request_id IS NOT NULL;

CREATE TABLE conversation_budget_account (
    tenant_id uuid NOT NULL,
    scope_type varchar(16) NOT NULL,
    scope_id uuid NOT NULL,
    limit_micro_usd bigint NOT NULL,
    reserved_micro_usd bigint NOT NULL DEFAULT 0,
    charged_micro_usd bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, scope_type, scope_id),
    CONSTRAINT ck_conversation_budget_scope CHECK (scope_type IN ('TENANT', 'MEMBERSHIP')),
    CONSTRAINT ck_conversation_budget_tenant_scope CHECK (
        scope_type <> 'TENANT' OR scope_id = tenant_id
    ),
    CONSTRAINT ck_conversation_budget_amounts CHECK (
        limit_micro_usd > 0
        AND reserved_micro_usd >= 0
        AND charged_micro_usd >= 0
        AND reserved_micro_usd <= limit_micro_usd
        AND charged_micro_usd <= limit_micro_usd
        AND reserved_micro_usd <= limit_micro_usd - charged_micro_usd
    ),
    CONSTRAINT ck_conversation_budget_version CHECK (version >= 1)
);

ALTER TABLE conversation_budget_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_budget_account FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_budget_account_tenant_isolation ON conversation_budget_account
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
