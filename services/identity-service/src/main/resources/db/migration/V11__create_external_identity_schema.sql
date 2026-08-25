ALTER TABLE identity_refresh_family
    DROP CONSTRAINT identity_refresh_family_authentication_method_check;

ALTER TABLE identity_refresh_family
    ADD CONSTRAINT identity_refresh_family_authentication_method_check
        CHECK (authentication_method IN ('LOCAL_PASSWORD', 'GOOGLE_OIDC'));

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
            'MFA_CHANGED',
            'EXTERNAL_IDENTITY_CHANGED'
        ));

CREATE TABLE identity_external_identity (
    external_identity_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    issuer VARCHAR(255) NOT NULL CHECK (issuer = 'https://accounts.google.com'),
    subject VARCHAR(255) NOT NULL CHECK (subject ~ '^[A-Za-z0-9_-]{1,255}$'),
    linked_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    unlinked_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_external_identity_lifecycle CHECK (
        unlinked_at IS NULL OR (linked_at <= unlinked_at AND unlinked_at = updated_at)
    )
);

CREATE UNIQUE INDEX identity_external_identity_active_subject_idx
    ON identity_external_identity(issuer, subject)
    WHERE unlinked_at IS NULL;

CREATE UNIQUE INDEX identity_external_identity_one_active_issuer_per_user_idx
    ON identity_external_identity(user_id, issuer)
    WHERE unlinked_at IS NULL;

CREATE INDEX identity_external_identity_user_history_idx
    ON identity_external_identity(user_id, created_at DESC, external_identity_id);

CREATE TABLE identity_oidc_evidence (
    evidence_id BYTEA PRIMARY KEY CHECK (octet_length(evidence_id) = 32),
    request_id UUID NOT NULL,
    operation VARCHAR(24) NOT NULL CHECK (operation IN ('ESTABLISH_SESSION', 'LINK')),
    workload_identity VARCHAR(64) NOT NULL CHECK (workload_identity = 'web-bff'),
    issuer VARCHAR(255) NOT NULL CHECK (issuer = 'https://accounts.google.com'),
    subject VARCHAR(255) NOT NULL CHECK (subject ~ '^[A-Za-z0-9_-]{1,255}$'),
    evidence_fingerprint BYTEA NOT NULL CHECK (octet_length(evidence_fingerprint) = 32),
    fingerprint_key_id VARCHAR(64) NOT NULL,
    fingerprint_version VARCHAR(32) NOT NULL CHECK (fingerprint_version = 'oidc-evidence-hmac-v1'),
    outcome VARCHAR(32) NOT NULL CHECK (outcome IN ('SESSION_ESTABLISHED', 'MFA_REQUIRED', 'LINKED', 'ACCOUNT_LINK_REQUIRED')),
    result_user_id UUID REFERENCES identity_user(user_id),
    result_key_id VARCHAR(64),
    result_nonce BYTEA CHECK (result_nonce IS NULL OR octet_length(result_nonce) = 12),
    result_ciphertext BYTEA CHECK (result_ciphertext IS NULL OR octet_length(result_ciphertext) >= 16),
    evidence_issued_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    retain_until TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT identity_oidc_evidence_lifetime CHECK (
        evidence_issued_at <= consumed_at + INTERVAL '30 seconds'
        AND retain_until >= consumed_at + INTERVAL '10 minutes'
    ),
    CONSTRAINT identity_oidc_evidence_result_cipher_pair CHECK (
        (result_key_id IS NULL) = (result_nonce IS NULL)
        AND (result_nonce IS NULL) = (result_ciphertext IS NULL)
        AND ((outcome = 'ACCOUNT_LINK_REQUIRED' AND result_ciphertext IS NULL)
             OR (outcome <> 'ACCOUNT_LINK_REQUIRED' AND result_ciphertext IS NOT NULL))
    ),
    UNIQUE(request_id, operation)
);

CREATE INDEX identity_oidc_evidence_retention_idx
    ON identity_oidc_evidence(retain_until, evidence_id);

CREATE INDEX identity_oidc_evidence_subject_idx
    ON identity_oidc_evidence(issuer, subject, consumed_at DESC);
