package com.sajtech.identity.application.erasure.model;

import java.time.Instant;
import java.util.UUID;

public record LegalHoldView(
    UUID holdId,
    UUID erasureRequestId,
    String state,
    String policyVersion,
    Instant createdAt,
    Instant releasedAt) {}
