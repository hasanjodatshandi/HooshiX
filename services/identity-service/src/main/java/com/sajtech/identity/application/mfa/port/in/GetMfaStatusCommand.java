package com.sajtech.identity.application.mfa.port.in;

import java.util.UUID;

public record GetMfaStatusCommand(UUID requestId, String refreshCredential) {}
