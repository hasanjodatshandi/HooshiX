package com.sajtech.identity.application.authentication.model;

import java.util.UUID;

public record RefreshSessionCommand(UUID requestId, String refreshCredential) {}
