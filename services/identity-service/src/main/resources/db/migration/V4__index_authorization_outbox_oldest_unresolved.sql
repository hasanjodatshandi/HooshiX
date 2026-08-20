CREATE INDEX identity_authorization_outbox_oldest_unresolved_idx
    ON identity_authorization_outbox (created_at, outbox_id)
    WHERE state IN ('PENDING', 'DISPATCHING');
