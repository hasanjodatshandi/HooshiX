CREATE TABLE identity_profile_command_dedup (
    request_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    operation VARCHAR(48) NOT NULL CHECK (operation IN (
        'UPDATE_PROFILE',
        'ADD_CONTACT',
        'RESEND_CONTACT_VERIFICATION',
        'VERIFY_CONTACT',
        'SET_PRIMARY_CONTACT',
        'REMOVE_CONTACT'
    )),
    intent_fingerprint BYTEA NOT NULL CHECK (octet_length(intent_fingerprint) = 32),
    fingerprint_version VARCHAR(32) NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN (
        'UPDATED', 'ACCEPTED', 'VERIFIED', 'REJECTED_PROOF', 'APPLIED'
    )),
    result_id UUID,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_profile_command_result_shape CHECK (
        (operation = 'ADD_CONTACT' AND outcome = 'ACCEPTED' AND result_id IS NOT NULL)
        OR
        (operation <> 'ADD_CONTACT' AND result_id IS NULL)
    )
);

CREATE INDEX identity_profile_command_user_time_idx
    ON identity_profile_command_dedup(user_id, created_at DESC, request_id);

CREATE TABLE identity_contact_verification_challenge (
    challenge_id UUID PRIMARY KEY,
    contact_id UUID NOT NULL REFERENCES identity_contact(contact_id),
    verifier BYTEA NOT NULL CHECK (octet_length(verifier) = 32),
    verifier_key_id VARCHAR(64) NOT NULL,
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('fa', 'en')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'USED', 'REPLACED', 'EXPIRED', 'EXHAUSTED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_sent_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX identity_contact_verification_contact_idx
    ON identity_contact_verification_challenge(contact_id, created_at DESC, challenge_id);

ALTER TABLE identity_notification_outbox
    DROP CONSTRAINT identity_notification_outbox_challenge_owner;

ALTER TABLE identity_notification_outbox
    ADD COLUMN contact_verification_challenge_id UUID
        REFERENCES identity_contact_verification_challenge(challenge_id);

ALTER TABLE identity_notification_outbox
    DROP CONSTRAINT identity_notification_outbox_content_type_check;

ALTER TABLE identity_notification_outbox
    ADD CONSTRAINT identity_notification_outbox_content_type_check CHECK (
        content_type IN ('REGISTRATION_VERIFICATION', 'PASSWORD_RECOVERY', 'CONTACT_VERIFICATION')
    );

ALTER TABLE identity_notification_outbox
    ADD CONSTRAINT identity_notification_outbox_challenge_owner CHECK (
        (challenge_id IS NOT NULL AND password_recovery_challenge_id IS NULL
            AND contact_verification_challenge_id IS NULL
            AND content_type = 'REGISTRATION_VERIFICATION')
        OR
        (challenge_id IS NULL AND password_recovery_challenge_id IS NOT NULL
            AND contact_verification_challenge_id IS NULL
            AND content_type = 'PASSWORD_RECOVERY')
        OR
        (challenge_id IS NULL AND password_recovery_challenge_id IS NULL
            AND contact_verification_challenge_id IS NOT NULL
            AND content_type = 'CONTACT_VERIFICATION')
    );
