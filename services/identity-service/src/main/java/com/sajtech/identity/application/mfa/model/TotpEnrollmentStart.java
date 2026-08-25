package com.sajtech.identity.application.mfa.model;

import java.time.Instant;

public record TotpEnrollmentStart(
    String enrollmentChallenge, String base32Secret, String otpauthUri, Instant expiresAt) {}
