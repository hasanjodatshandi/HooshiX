package com.sajtech.identity.application.password.port.in;

import java.util.UUID;

public record ChangePasswordCommand(
    UUID requestId, String refreshCredential, String currentPassword, String newPassword) {}
