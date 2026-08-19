package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record LogoutAllCommand(UUID requestId, String refreshCredential) {}
