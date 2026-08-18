CREATE TABLE identity_user (
    user_id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE')),
    created_at TIMESTAMPTZ(6) NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL
);

CREATE TABLE identity_profile (
    user_id UUID PRIMARY KEY REFERENCES identity_user(user_id) ON DELETE RESTRICT,
    first_name VARCHAR(480) NOT NULL,
    last_name VARCHAR(480) NOT NULL,
    father_name VARCHAR(480),
    CHECK (char_length(first_name) > 0),
    CHECK (char_length(last_name) > 0)
);

CREATE TABLE identity_contact (
    contact_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id) ON DELETE RESTRICT,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('EMAIL', 'PHONE')),
    canonical_value VARCHAR(512) NOT NULL,
    delivery_value VARCHAR(512) NOT NULL,
    reservation_expires_at TIMESTAMPTZ(6) NOT NULL,
    verified_at TIMESTAMPTZ(6),
    primary_contact BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ(6) NOT NULL,
    UNIQUE (kind, canonical_value)
);

CREATE UNIQUE INDEX ux_identity_contact_primary_per_user
    ON identity_contact(user_id) WHERE primary_contact;

CREATE TABLE identity_credential (
    user_id UUID PRIMARY KEY REFERENCES identity_user(user_id) ON DELETE RESTRICT,
    password_hash VARCHAR(512) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL
);

CREATE TABLE identity_registration_challenge (
    challenge_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id) ON DELETE RESTRICT,
    contact_id UUID NOT NULL REFERENCES identity_contact(contact_id) ON DELETE RESTRICT,
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('fa', 'en')),
    verifier_key_id VARCHAR(64) NOT NULL,
    verifier_digest BYTEA NOT NULL CHECK (octet_length(verifier_digest) = 32),
    expires_at TIMESTAMPTZ(6) NOT NULL,
    resend_not_before TIMESTAMPTZ(6) NOT NULL,
    failed_attempts SMALLINT NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    consumed_at TIMESTAMPTZ(6),
    created_at TIMESTAMPTZ(6) NOT NULL
);

CREATE INDEX ix_identity_registration_challenge_contact_created
    ON identity_registration_challenge(contact_id, created_at DESC);

CREATE TABLE identity_request_idempotency (
    request_id UUID NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    fingerprint_version SMALLINT NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    fingerprint_digest BYTEA NOT NULL CHECK (octet_length(fingerprint_digest) = 32),
    outcome VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ(6) NOT NULL,
    PRIMARY KEY (request_id, purpose)
);

CREATE TABLE identity_notification_outbox (
    outbox_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES identity_user(user_id) ON DELETE RESTRICT,
    escrow_key_id VARCHAR(64) NOT NULL,
    escrow_nonce BYTEA NOT NULL CHECK (octet_length(escrow_nonce) = 12),
    escrow_ciphertext BYTEA NOT NULL,
    message_not_after TIMESTAMPTZ(6) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING', 'PROCESSING', 'ACKNOWLEDGED', 'HANDOFF_FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ(6) NOT NULL,
    lease_until TIMESTAMPTZ(6),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ(6) NOT NULL,
    updated_at TIMESTAMPTZ(6) NOT NULL
);

CREATE INDEX ix_identity_notification_outbox_claim
    ON identity_notification_outbox(state, next_attempt_at, created_at);
