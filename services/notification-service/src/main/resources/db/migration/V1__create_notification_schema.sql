CREATE TABLE notification_template_definition (
    definition_id UUID PRIMARY KEY,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    semantic_type VARCHAR(64) NOT NULL CHECK (
        semantic_type IN (
            'REGISTRATION_VERIFICATION_CODE',
            'PASSWORD_RECOVERY_CODE',
            'MFA_VERIFICATION_CODE',
            'PASSWORD_CHANGED_NOTICE'
        )
    ),
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('en', 'fa')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (channel, semantic_type, locale)
);

CREATE TABLE notification_template_version (
    version_id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES notification_template_definition(definition_id),
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    subject_template VARCHAR(200),
    text_template VARCHAR(4096) NOT NULL,
    html_template VARCHAR(8192),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (definition_id, version_number)
);

CREATE TABLE notification_template_activation (
    definition_id UUID PRIMARY KEY REFERENCES notification_template_definition(definition_id),
    active_version_id UUID NOT NULL REFERENCES notification_template_version(version_id),
    previous_version_id UUID REFERENCES notification_template_version(version_id),
    generation BIGINT NOT NULL CHECK (generation > 0),
    activated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE notification_template_audit (
    event_id UUID PRIMARY KEY,
    definition_id UUID NOT NULL REFERENCES notification_template_definition(definition_id),
    version_id UUID REFERENCES notification_template_version(version_id),
    action VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE notification (
    notification_id UUID PRIMARY KEY,
    caller_service VARCHAR(64) NOT NULL,
    request_id UUID NOT NULL,
    intent_fingerprint BYTEA NOT NULL CHECK (octet_length(intent_fingerprint) = 32),
    fingerprint_version VARCHAR(32) NOT NULL,
    fingerprint_key_id VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL CHECK (channel IN ('EMAIL', 'SMS')),
    semantic_type VARCHAR(64) NOT NULL,
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('en', 'fa')),
    template_version_id UUID NOT NULL REFERENCES notification_template_version(version_id),
    template_sha256 CHAR(64) NOT NULL CHECK (template_sha256 ~ '^[0-9a-f]{64}$'),
    message_not_after TIMESTAMP(6) WITH TIME ZONE,
    effective_delivery_deadline TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lifecycle VARCHAR(32) NOT NULL CHECK (
        lifecycle IN (
            'ACCEPTED',
            'DISPATCHING',
            'RETRY_WAIT',
            'PROVIDER_ACCEPTED',
            'DELIVERED',
            'FAILED_PERMANENT',
            'EXPIRED',
            'DELIVERY_STATUS_UNKNOWN'
        )
    ),
    escrow_format_version INTEGER NOT NULL CHECK (escrow_format_version = 1),
    escrow_key_id VARCHAR(64) NOT NULL,
    recipient_nonce BYTEA NOT NULL CHECK (octet_length(recipient_nonce) = 12),
    recipient_ciphertext BYTEA NOT NULL CHECK (octet_length(recipient_ciphertext) >= 16),
    subject_nonce BYTEA CHECK (subject_nonce IS NULL OR octet_length(subject_nonce) = 12),
    subject_ciphertext BYTEA CHECK (subject_ciphertext IS NULL OR octet_length(subject_ciphertext) >= 16),
    text_nonce BYTEA NOT NULL CHECK (octet_length(text_nonce) = 12),
    text_ciphertext BYTEA NOT NULL CHECK (octet_length(text_ciphertext) >= 16),
    html_nonce BYTEA CHECK (html_nonce IS NULL OR octet_length(html_nonce) = 12),
    html_ciphertext BYTEA CHECK (html_ciphertext IS NULL OR octet_length(html_ciphertext) >= 16),
    accepted_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sensitive_expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT notification_request_identity UNIQUE (caller_service, request_id),
    CONSTRAINT notification_subject_cipher_pair CHECK ((subject_nonce IS NULL) = (subject_ciphertext IS NULL)),
    CONSTRAINT notification_html_cipher_pair CHECK ((html_nonce IS NULL) = (html_ciphertext IS NULL)),
    CONSTRAINT notification_sensitive_retention CHECK (sensitive_expires_at <= accepted_at + INTERVAL '24 hours')
);

CREATE TABLE notification_attempt (
    attempt_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification(notification_id),
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 4),
    execution_id UUID,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING', 'DISPATCHING', 'RECONCILING', 'COMPLETED')),
    classification VARCHAR(40) CHECK (
        classification IS NULL OR classification IN (
            'DEFINITIVE_ACCEPTED',
            'DEFINITIVE_TRANSIENT_FAILURE',
            'DEFINITIVE_PERMANENT_FAILURE',
            'AMBIGUOUS'
        )
    ),
    next_action_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    claimed_until TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (notification_id, attempt_number)
);

CREATE INDEX notification_attempt_due_idx
    ON notification_attempt (next_action_at, attempt_id)
    WHERE state IN ('PENDING', 'RECONCILING');

CREATE TABLE provider_receipt_evidence (
    receipt_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification(notification_id),
    attempt_id UUID NOT NULL REFERENCES notification_attempt(attempt_id),
    provider_code VARCHAR(64),
    provider_correlation_id VARCHAR(256),
    observed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE notification_result_outbox (
    outbox_id UUID PRIMARY KEY,
    notification_id UUID NOT NULL REFERENCES notification(notification_id),
    terminal_lifecycle VARCHAR(32) NOT NULL CHECK (
        terminal_lifecycle IN ('DELIVERED', 'FAILED_PERMANENT', 'EXPIRED', 'DELIVERY_STATUS_UNKNOWN')
    ),
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    delivered_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    UNIQUE (notification_id)
);
