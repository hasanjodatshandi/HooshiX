ALTER TABLE identity_password_recovery_challenge
    ADD COLUMN request_id UUID,
    ADD COLUMN verifier_key_id VARCHAR(64),
    ADD COLUMN completed_request_id UUID;

CREATE UNIQUE INDEX identity_password_recovery_request_idx
    ON identity_password_recovery_challenge(request_id)
    WHERE request_id IS NOT NULL;

CREATE UNIQUE INDEX identity_password_recovery_completed_request_idx
    ON identity_password_recovery_challenge(completed_request_id)
    WHERE completed_request_id IS NOT NULL;

ALTER TABLE identity_notification_outbox
    ADD COLUMN password_recovery_challenge_id UUID
        REFERENCES identity_password_recovery_challenge(challenge_id),
    ADD COLUMN content_type VARCHAR(32) NOT NULL DEFAULT 'REGISTRATION_VERIFICATION'
        CHECK (content_type IN ('REGISTRATION_VERIFICATION', 'PASSWORD_RECOVERY'));

ALTER TABLE identity_notification_outbox
    ALTER COLUMN challenge_id DROP NOT NULL;

ALTER TABLE identity_notification_outbox
    ADD CONSTRAINT identity_notification_outbox_challenge_owner CHECK (
        (challenge_id IS NOT NULL AND password_recovery_challenge_id IS NULL
            AND content_type = 'REGISTRATION_VERIFICATION')
        OR
        (challenge_id IS NULL AND password_recovery_challenge_id IS NOT NULL
            AND content_type = 'PASSWORD_RECOVERY')
    );

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
            'PASSWORD_CHANGED'
        ));
