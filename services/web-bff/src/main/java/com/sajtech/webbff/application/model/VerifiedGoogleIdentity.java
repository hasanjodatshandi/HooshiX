package com.sajtech.webbff.application.model;

public record VerifiedGoogleIdentity(
    String issuer,
    String subject,
    String email,
    boolean emailVerified,
    String givenName,
    String familyName) {}
