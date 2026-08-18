package com.sajtech.identity.application.notificationhandoff.model;

import com.sajtech.identity.application.registration.model.EscrowCiphertext;
import java.time.Instant;
import java.util.UUID;

public record OutboxClaim(
    UUID outboxId,
    UUID requestId,
    EscrowCiphertext escrow,
    Instant messageNotAfter,
    int attemptCount,
    Instant createdAt) {}
