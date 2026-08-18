package com.sajtech.identity.application.registration.model;

public record IdempotencyRecord(RequestFingerprint fingerprint, String outcome) {}
