package com.sajtech.webbff.application.model;

import java.time.Instant;

public record OidcPreauthTransaction(
    OidcPurpose purpose,
    String browserSessionLocator,
    String nonce,
    String verifier,
    String redirectUri,
    String returnTarget,
    Instant createdAt,
    Instant expiresAt) {}
