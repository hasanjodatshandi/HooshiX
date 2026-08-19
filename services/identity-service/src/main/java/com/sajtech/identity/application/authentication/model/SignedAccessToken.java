package com.sajtech.identity.application.authentication.model;

import java.time.Instant;

public record SignedAccessToken(String token, Instant expiresAt) {}
