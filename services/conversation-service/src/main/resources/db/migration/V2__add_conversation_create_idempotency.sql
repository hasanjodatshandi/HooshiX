ALTER TABLE conversation
    ADD COLUMN create_request_id uuid;

UPDATE conversation
SET aggregate_version = 1
WHERE aggregate_version = 0;

ALTER TABLE conversation
    ALTER COLUMN aggregate_version SET DEFAULT 1,
    DROP CONSTRAINT ck_conversation_aggregate_version,
    ADD CONSTRAINT ck_conversation_aggregate_version CHECK (aggregate_version >= 1);

ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_id_uuid_v4 CHECK (
        (get_byte(uuid_send(conversation_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(conversation_id), 8) & 192) = 128
    );

ALTER TABLE conversation_message
    ADD CONSTRAINT ck_conversation_message_id_uuid_v4 CHECK (
        (get_byte(uuid_send(message_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(message_id), 8) & 192) = 128
    );

ALTER TABLE conversation_model_run
    ADD CONSTRAINT ck_conversation_model_run_id_uuid_v4 CHECK (
        (get_byte(uuid_send(run_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(run_id), 8) & 192) = 128
    );

ALTER TABLE conversation
    ADD CONSTRAINT ck_conversation_create_request_uuid_v4 CHECK (
        create_request_id IS NULL
        OR (
            (get_byte(uuid_send(create_request_id), 6) >> 4) = 4
            AND (get_byte(uuid_send(create_request_id), 8) & 192) = 128
        )
    );

CREATE UNIQUE INDEX uq_conversation_create_request
    ON conversation (tenant_id, owner_membership_id, create_request_id)
    WHERE create_request_id IS NOT NULL;

CREATE TABLE conversation_mutation_request (
    tenant_id uuid NOT NULL,
    owner_membership_id uuid NOT NULL,
    request_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    operation varchar(16) NOT NULL,
    expected_version bigint NOT NULL,
    resulting_version bigint NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id, owner_membership_id, request_id),
    CONSTRAINT fk_conversation_mutation_request_conversation
        FOREIGN KEY (conversation_id, tenant_id)
        REFERENCES conversation (conversation_id, tenant_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_conversation_mutation_request_uuid_v4 CHECK (
        (get_byte(uuid_send(request_id), 6) >> 4) = 4
        AND (get_byte(uuid_send(request_id), 8) & 192) = 128
    ),
    CONSTRAINT ck_conversation_mutation_request_operation CHECK (
        operation IN ('ARCHIVE', 'DELETE')
    ),
    CONSTRAINT ck_conversation_mutation_request_versions CHECK (
        expected_version >= 1 AND resulting_version = expected_version + 1
    )
);

ALTER TABLE conversation_mutation_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE conversation_mutation_request FORCE ROW LEVEL SECURITY;

CREATE POLICY conversation_mutation_request_tenant_isolation ON conversation_mutation_request
    FOR ALL
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
