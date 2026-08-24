package com.sajtech.identity.application.password.port.in;

import java.time.Instant;

public record PasswordChangeSession(
    String refreshCredential, Instant idleExpiresAt, Instant absoluteExpiresAt) {}
