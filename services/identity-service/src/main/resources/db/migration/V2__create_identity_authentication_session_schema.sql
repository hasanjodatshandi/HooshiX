CREATE TABLE identity_refresh_family (
    refresh_family_id UUID PRIMARY KEY,
    session_id VARCHAR(43) NOT NULL UNIQUE
        CHECK (session_id ~ '^[A-Za-z0-9_-]{43}$'),
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'REVOKED')),
    session_mode VARCHAR(32) NOT NULL CHECK (session_mode = 'AUTHENTICATED_ONBOARDING'),
    authentication_method VARCHAR(32) NOT NULL CHECK (authentication_method = 'LOCAL_PASSWORD'),
    authenticated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_activity_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    idle_expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    absolute_expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP(6) WITH TIME ZONE,
    revocation_reason VARCHAR(32)
        CHECK (revocation_reason IS NULL OR revocation_reason IN (
            'ACTIVE_FAMILY_LIMIT',
            'LOGOUT_CURRENT',
            'LOGOUT_ALL',
            'REFRESH_REUSE',
            'EXPIRED',
            'USER_INACTIVE'
        )),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_refresh_family_time_order CHECK (
        authenticated_at <= created_at
        AND created_at <= last_activity_at
        AND last_activity_at <= idle_expires_at
        AND idle_expires_at <= absolute_expires_at
        AND absolute_expires_at = created_at + INTERVAL '30 days'
        AND idle_expires_at <= last_activity_at + INTERVAL '7 days'
    ),
    CONSTRAINT identity_refresh_family_revocation_pair CHECK (
        (state = 'ACTIVE' AND revoked_at IS NULL AND revocation_reason IS NULL)
        OR (state = 'REVOKED' AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    )
);

CREATE INDEX identity_refresh_family_active_user_idx
    ON identity_refresh_family (user_id, created_at, refresh_family_id)
    WHERE state = 'ACTIVE';

CREATE INDEX identity_refresh_family_retention_idx
    ON identity_refresh_family (absolute_expires_at, refresh_family_id);

CREATE TABLE identity_refresh_credential (
    credential_id UUID PRIMARY KEY,
    refresh_family_id UUID NOT NULL
        REFERENCES identity_refresh_family(refresh_family_id) ON DELETE CASCADE,
    token_digest BYTEA NOT NULL CHECK (octet_length(token_digest) = 32),
    digest_key_id VARCHAR(64) NOT NULL,
    digest_version VARCHAR(32) NOT NULL CHECK (digest_version = 'refresh-hmac-v1'),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    issued_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    retired_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT identity_refresh_credential_retired_pair CHECK (
        (state = 'ACTIVE' AND retired_at IS NULL)
        OR (state IN ('ROTATED', 'REVOKED') AND retired_at IS NOT NULL)
    ),
    UNIQUE (digest_key_id, digest_version, token_digest)
);

CREATE UNIQUE INDEX identity_refresh_credential_one_active_idx
    ON identity_refresh_credential (refresh_family_id)
    WHERE state = 'ACTIVE';

CREATE INDEX identity_refresh_credential_family_idx
    ON identity_refresh_credential (refresh_family_id, issued_at, credential_id);
