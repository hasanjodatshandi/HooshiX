CREATE TABLE identity_password_recovery_challenge (
    challenge_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES identity_user(user_id),
    contact_id UUID NOT NULL REFERENCES identity_contact(contact_id),
    verifier BYTEA NOT NULL CHECK (octet_length(verifier) = 32),
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE', 'USED', 'EXPIRED', 'EXHAUSTED')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts BETWEEN 0 AND 5),
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX identity_password_recovery_contact_idx
    ON identity_password_recovery_challenge(contact_id, created_at DESC, challenge_id);
