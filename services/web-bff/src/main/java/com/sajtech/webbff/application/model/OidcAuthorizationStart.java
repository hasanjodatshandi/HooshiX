package com.sajtech.webbff.application.model;

import java.time.Instant;

public record OidcAuthorizationStart(
    String preauthCookie,
    String state,
    String nonce,
    String verifier,
    String codeChallenge,
    Instant expiresAt) {}
