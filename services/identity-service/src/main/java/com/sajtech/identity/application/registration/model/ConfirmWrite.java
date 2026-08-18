package com.sajtech.identity.application.registration.model;

import java.time.Instant;
import java.util.UUID;

public record ConfirmWrite(
    UUID requestId,
    RequestFingerprint fingerprint,
    PendingRegistrationSnapshot expected,
    Instant confirmedAt) {}
