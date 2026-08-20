package com.sajtech.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

public interface AuthorizationOutboxTelemetryQuery {
  Optional<Instant> oldestUnresolvedAuthorizationOutboxCreatedAt();
}
