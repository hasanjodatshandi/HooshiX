ALTER TABLE conversation
    ADD COLUMN create_request_id uuid;

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
