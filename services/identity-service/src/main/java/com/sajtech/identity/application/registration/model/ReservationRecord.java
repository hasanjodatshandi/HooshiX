package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Instant;
import java.util.UUID;

public record ReservationRecord(
    UUID userId,
    UUID contactId,
    UUID challengeId,
    RegistrationLocale locale,
    String deliveryValue,
    Instant expiresAt,
    Instant lastSentAt) {}
