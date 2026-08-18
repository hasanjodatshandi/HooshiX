package com.sajtech.identity.application.registration.model;

import java.time.Instant;
import java.util.UUID;

public record ResendWrite(
    UUID requestId,
    RequestFingerprint fingerprint,
    PendingRegistrationSnapshot expected,
    UUID replacementChallengeId,
    UUID outboxId,
    UUID notificationRequestId,
    ChallengeVerifier verifier,
    EscrowCiphertext escrow,
    Instant createdAt,
    Instant challengeExpiresAt,
    Instant resendNotBefore) {}
