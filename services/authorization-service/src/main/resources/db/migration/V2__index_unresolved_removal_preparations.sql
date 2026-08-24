CREATE INDEX authorization_idempotency_unresolved_prepare_idx
  ON authorization_idempotency_record(created_at,request_id)
  WHERE operation='PREPARE_REMOVAL';
