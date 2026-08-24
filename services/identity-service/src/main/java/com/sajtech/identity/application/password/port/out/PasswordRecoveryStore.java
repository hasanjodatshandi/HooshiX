package com.sajtech.identity.application.password.port.out;
import java.util.UUID;
import java.time.Instant;
public interface PasswordRecoveryStore { UUID create(UUID userId, UUID contactId, byte[] verifier, String keyId, Instant expiresAt); }
