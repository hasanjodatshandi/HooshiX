ALTER TABLE identity_notification_outbox
    ADD COLUMN notification_id UUID,
    ADD COLUMN notification_terminal_lifecycle VARCHAR(32),
    ADD COLUMN notification_result_at TIMESTAMP(6) WITH TIME ZONE,
    ADD CONSTRAINT identity_notification_terminal_lifecycle_valid CHECK (
        notification_terminal_lifecycle IS NULL OR notification_terminal_lifecycle IN (
            'DELIVERED', 'FAILED_PERMANENT', 'EXPIRED', 'DELIVERY_STATUS_UNKNOWN'
        )
    ),
    ADD CONSTRAINT identity_notification_result_pair CHECK (
        (notification_terminal_lifecycle IS NULL) = (notification_result_at IS NULL)
    );

CREATE UNIQUE INDEX identity_notification_outbox_notification_id_uq
    ON identity_notification_outbox(notification_id)
    WHERE notification_id IS NOT NULL;
