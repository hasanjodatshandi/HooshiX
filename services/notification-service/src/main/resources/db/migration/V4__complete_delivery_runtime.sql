ALTER TABLE notification_attempt
    ADD COLUMN dispatched_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN observation_started_at TIMESTAMP(6) WITH TIME ZONE,
    ADD CONSTRAINT notification_attempt_dispatch_identity CHECK (
        (execution_id IS NULL AND dispatched_at IS NULL)
        OR (execution_id IS NOT NULL AND dispatched_at IS NOT NULL)
    );

ALTER TABLE notification
    ALTER COLUMN recipient_nonce DROP NOT NULL,
    ALTER COLUMN recipient_ciphertext DROP NOT NULL,
    ALTER COLUMN text_nonce DROP NOT NULL,
    ALTER COLUMN text_ciphertext DROP NOT NULL,
    ADD CONSTRAINT notification_recipient_cipher_pair CHECK (
        (recipient_nonce IS NULL) = (recipient_ciphertext IS NULL)
    ),
    ADD CONSTRAINT notification_text_cipher_pair CHECK (
        (text_nonce IS NULL) = (text_ciphertext IS NULL)
    );

ALTER TABLE notification_result_outbox
    ADD COLUMN exhausted_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN last_error_class VARCHAR(64),
    ADD CONSTRAINT notification_result_outbox_completion_exclusive CHECK (
        NOT (completed_at IS NOT NULL AND exhausted_at IS NOT NULL)
    );

DROP INDEX notification_result_outbox_pending_idx;
CREATE INDEX notification_result_outbox_pending_idx
    ON notification_result_outbox (next_attempt_at, outbox_id)
    WHERE completed_at IS NULL AND exhausted_at IS NULL;

CREATE INDEX notification_attempt_stale_dispatch_idx
    ON notification_attempt (claimed_until, attempt_id)
    WHERE state = 'DISPATCHING';

CREATE INDEX notification_attempt_reconciliation_idx
    ON notification_attempt (next_action_at, attempt_id)
    WHERE state = 'RECONCILING';
