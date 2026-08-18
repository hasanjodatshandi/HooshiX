package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.CanonicalContact;
import com.sajtech.identity.domain.registration.RegistrationLocale;
import com.sajtech.identity.domain.registration.RegistrationProfile;
import java.time.Instant;
import java.util.UUID;

public record RegistrationWrite(
    UUID requestId,
    RequestFingerprint fingerprint,
    UUID userId,
    UUID contactId,
    UUID challengeId,
    UUID outboxId,
    UUID notificationRequestId,
    CanonicalContact contact,
    RegistrationProfile profile,
    String passwordHash,
    RegistrationLocale locale,
    ChallengeVerifier verifier,
    EscrowCiphertext escrow,
    Instant createdAt,
    Instant challengeExpiresAt,
    Instant resendNotBefore) {}
