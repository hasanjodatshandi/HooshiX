CREATE TABLE identity_user (
    user_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'DELETING', 'DELETED')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE identity_profile (
    user_id UUID PRIMARY KEY REFERENCES identity_user(user_id),
    first_name VARCHAR(480) NOT NULL,
    last_name VARCHAR(480) NOT NULL,
    father_name VARCHAR(480),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE identity_contact (
    contact_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    contact_type VARCHAR(16) NOT NULL CHECK (contact_type IN ('EMAIL', 'PHONE')),
    canonical_value VARCHAR(254) NOT NULL,
    delivery_value VARCHAR(254) NOT NULL,
    verified_at TIMESTAMP(6) WITH TIME ZONE,
    primary_active BOOLEAN NOT NULL DEFAULT FALSE,
    removed_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX identity_contact_verified_unique_idx
    ON identity_contact (contact_type, canonical_value)
    WHERE verified_at IS NOT NULL AND removed_at IS NULL;

CREATE UNIQUE INDEX identity_contact_one_primary_per_user_idx
    ON identity_contact (user_id)
    WHERE primary_active = TRUE AND removed_at IS NULL;

CREATE INDEX identity_contact_user_idx
    ON identity_contact (user_id, created_at, contact_id);

CREATE TABLE identity_credential (
    user_id UUID PRIMARY KEY REFERENCES identity_user(user_id),
    password_hash VARCHAR(512) NOT NULL,
    algorithm VARCHAR(16) NOT NULL CHECK (algorithm = 'ARGON2ID'),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE registration_challenge (
    challenge_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
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

CREATE INDEX registration_challenge_contact_idx
    ON registration_challenge (contact_id, created_at DESC, challenge_id);

CREATE TABLE registration_reservation (
    contact_type VARCHAR(16) NOT NULL CHECK (contact_type IN ('EMAIL', 'PHONE')),
    canonical_value VARCHAR(254) NOT NULL,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    contact_id UUID NOT NULL REFERENCES identity_contact(contact_id),
    challenge_id UUID NOT NULL REFERENCES registration_challenge(challenge_id),
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (contact_type, canonical_value)
);

CREATE TABLE identity_command_dedup (
    request_id UUID PRIMARY KEY,
    operation VARCHAR(48) NOT NULL CHECK (operation IN ('REGISTER', 'RESEND_REGISTRATION_VERIFICATION', 'CONFIRM_REGISTRATION')),
    intent_fingerprint BYTEA NOT NULL CHECK (octet_length(intent_fingerprint) = 32),
    fingerprint_version VARCHAR(32) NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('ACCEPTED', 'CONFIRMED', 'REJECTED_PROOF')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE identity_notification_outbox (
    outbox_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    challenge_id UUID NOT NULL REFERENCES registration_challenge(challenge_id),
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('fa', 'en')),
    escrow_format_version INTEGER NOT NULL CHECK (escrow_format_version = 1),
    escrow_key_id VARCHAR(64) NOT NULL,
    payload_nonce BYTEA CHECK (payload_nonce IS NULL OR octet_length(payload_nonce) = 12),
    payload_ciphertext BYTEA CHECK (payload_ciphertext IS NULL OR octet_length(payload_ciphertext) >= 16),
    message_not_after TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sensitive_expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'CLAIMED', 'SUBMITTED', 'FAILED_PERMANENT')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_until TIMESTAMP(6) WITH TIME ZONE,
    submitted_at TIMESTAMP(6) WITH TIME ZONE,
    last_error_class VARCHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_notification_outbox_cipher_pair CHECK ((payload_nonce IS NULL) = (payload_ciphertext IS NULL)),
    CONSTRAINT identity_notification_outbox_retention CHECK (sensitive_expires_at <= created_at + INTERVAL '24 hours')
);

CREATE INDEX identity_notification_outbox_due_idx
    ON identity_notification_outbox (next_attempt_at, outbox_id)
    WHERE state IN ('PENDING', 'CLAIMED');

CREATE TABLE identity_security_audit (
    event_id UUID PRIMARY KEY,
    event_code VARCHAR(64) NOT NULL,
    user_id UUID,
    contact_id UUID,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX identity_security_audit_time_idx
    ON identity_security_audit (occurred_at, event_id);
