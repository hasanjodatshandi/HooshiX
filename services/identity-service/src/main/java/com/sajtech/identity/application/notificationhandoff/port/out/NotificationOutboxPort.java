package com.sajtech.identity.application.notificationhandoff.port.out;

import com.sajtech.identity.application.notificationhandoff.model.OutboxClaim;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationOutboxPort {
  Optional<OutboxClaim> claim(Instant now, Instant leaseUntil);
  void acknowledge(UUID outboxId, Instant now);
  void retry(UUID outboxId, String machineCode, Instant nextAttemptAt, Instant now);
  void failPermanently(UUID outboxId, String machineCode, Instant now);
}
