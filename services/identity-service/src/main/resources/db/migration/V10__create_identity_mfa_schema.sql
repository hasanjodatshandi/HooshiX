ALTER TABLE identity_refresh_family
    ADD COLUMN mfa_authenticated_at TIMESTAMP(6) WITH TIME ZONE,
    ADD CONSTRAINT identity_refresh_family_mfa_time CHECK (
        mfa_authenticated_at IS NULL
        OR (authenticated_at <= mfa_authenticated_at AND mfa_authenticated_at <= updated_at)
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
            'PASSWORD_CHANGED',
            'MFA_CHANGED'
        ));

CREATE TABLE identity_totp_enrollment (
    enrollment_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'REPLACED', 'DISABLED')),
    secret_key_id VARCHAR(64) NOT NULL,
    secret_version VARCHAR(32) NOT NULL CHECK (secret_version = 'mfa-aes-gcm-v1'),
    secret_nonce BYTEA NOT NULL CHECK (octet_length(secret_nonce) = 12),
    secret_ciphertext BYTEA NOT NULL CHECK (octet_length(secret_ciphertext) = 48),
    last_accepted_timestep BIGINT CHECK (last_accepted_timestep IS NULL OR last_accepted_timestep >= 0),
    activated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    ended_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_totp_enrollment_state_time CHECK (
        (state = 'ACTIVE' AND ended_at IS NULL)
        OR (state IN ('REPLACED', 'DISABLED') AND ended_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX identity_totp_enrollment_one_active_user_idx
    ON identity_totp_enrollment(user_id)
    WHERE state = 'ACTIVE';

CREATE INDEX identity_totp_enrollment_user_time_idx
    ON identity_totp_enrollment(user_id, created_at DESC, enrollment_id);

CREATE TABLE identity_totp_pending_enrollment (
    pending_enrollment_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    replaces_enrollment_id UUID REFERENCES identity_totp_enrollment(enrollment_id),
    challenge_digest BYTEA NOT NULL CHECK (octet_length(challenge_digest) = 32),
    digest_key_id VARCHAR(64) NOT NULL,
    digest_version VARCHAR(32) NOT NULL CHECK (digest_version = 'mfa-challenge-hmac-v1'),
    secret_key_id VARCHAR(64) NOT NULL,
    secret_version VARCHAR(32) NOT NULL CHECK (secret_version = 'mfa-aes-gcm-v1'),
    secret_nonce BYTEA NOT NULL CHECK (octet_length(secret_nonce) = 12),
    secret_ciphertext BYTEA NOT NULL CHECK (octet_length(secret_ciphertext) = 48),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'CONFIRMED', 'REPLACED', 'EXPIRED', 'EXHAUSTED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    current_proof_verified_at TIMESTAMP(6) WITH TIME ZONE,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_totp_pending_replacement_proof CHECK (
        (replaces_enrollment_id IS NULL AND current_proof_verified_at IS NULL)
        OR (replaces_enrollment_id IS NOT NULL AND current_proof_verified_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX identity_totp_pending_one_active_user_idx
    ON identity_totp_pending_enrollment(user_id)
    WHERE state = 'ACTIVE';

CREATE UNIQUE INDEX identity_totp_pending_challenge_idx
    ON identity_totp_pending_enrollment(digest_key_id, digest_version, challenge_digest);

CREATE INDEX identity_totp_pending_expiry_idx
    ON identity_totp_pending_enrollment(expires_at, pending_enrollment_id);

CREATE TABLE identity_mfa_recovery_code (
    recovery_code_id UUID PRIMARY KEY,
    enrollment_id UUID NOT NULL REFERENCES identity_totp_enrollment(enrollment_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    code_digest BYTEA NOT NULL CHECK (octet_length(code_digest) = 32),
    digest_key_id VARCHAR(64) NOT NULL,
    digest_version VARCHAR(32) NOT NULL CHECK (digest_version = 'mfa-recovery-hmac-v1'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'USED', 'REVOKED')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT identity_mfa_recovery_code_state_time CHECK (
        (state = 'ACTIVE' AND consumed_at IS NULL)
        OR (state IN ('USED', 'REVOKED') AND consumed_at IS NOT NULL)
    ),
    UNIQUE (digest_key_id, digest_version, code_digest)
);

CREATE INDEX identity_mfa_recovery_code_active_user_idx
    ON identity_mfa_recovery_code(user_id, recovery_code_id)
    WHERE state = 'ACTIVE';

CREATE TABLE identity_mfa_login_challenge (
    challenge_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    locator_digest BYTEA NOT NULL CHECK (octet_length(locator_digest) = 32),
    digest_key_id VARCHAR(64) NOT NULL,
    digest_version VARCHAR(32) NOT NULL CHECK (digest_version = 'mfa-challenge-hmac-v1'),
    authentication_method VARCHAR(32) NOT NULL CHECK (authentication_method IN ('LOCAL_PASSWORD', 'GOOGLE_OIDC')),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'USED', 'SUPERSEDED', 'EXPIRED', 'EXHAUSTED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    primary_authenticated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX identity_mfa_login_one_active_user_idx
    ON identity_mfa_login_challenge(user_id)
    WHERE state = 'ACTIVE';

CREATE UNIQUE INDEX identity_mfa_login_locator_idx
    ON identity_mfa_login_challenge(digest_key_id, digest_version, locator_digest);

CREATE INDEX identity_mfa_login_expiry_idx
    ON identity_mfa_login_challenge(expires_at, challenge_id);
