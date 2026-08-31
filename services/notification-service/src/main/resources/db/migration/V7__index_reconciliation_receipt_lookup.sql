CREATE INDEX provider_receipt_attempt_latest_idx
    ON provider_receipt_evidence (attempt_id, observed_at DESC, receipt_id DESC)
    INCLUDE (provider_correlation_id);
