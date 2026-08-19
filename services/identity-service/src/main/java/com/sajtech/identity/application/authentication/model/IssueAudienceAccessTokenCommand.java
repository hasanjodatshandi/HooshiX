package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record IssueAudienceAccessTokenCommand(
    UUID requestId, String refreshCredential, String audience) {}
