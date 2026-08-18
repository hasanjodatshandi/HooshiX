package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.ContactKind;
import com.sajtech.identity.domain.registration.RegistrationLocale;
import java.time.Instant;
import java.util.UUID;

public record PendingRegistrationSnapshot(
    UUID userId,
    UUID contactId,
    UUID challengeId,
    ContactKind contactKind,
    String deliveryValue,
    RegistrationLocale locale,
    ChallengeVerifier verifier,
    Instant expiresAt,
    Instant resendNotBefore,
    int failedAttempts) {}
