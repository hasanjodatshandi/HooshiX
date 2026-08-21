package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record LogoutCurrentCommand(UUID requestId, String refreshCredential) {}
