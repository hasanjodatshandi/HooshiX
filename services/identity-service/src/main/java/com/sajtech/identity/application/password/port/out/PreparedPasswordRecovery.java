package com.sajtech.identity.application.password.port.out;

import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Instant;
import java.util.UUID;

public record PreparedPasswordRecovery(
    UUID requestId,
    UUID challengeId,
    UUID outboxId,
    UUID notificationRequestId,
    UUID userId,
    UUID contactId,
    CanonicalContact contact,
    RegistrationLocale locale,
    byte[] verifier,
    String verifierKeyId,
    EncryptedHandoff handoff,
    Instant createdAt,
    Instant expiresAt) {
  public PreparedPasswordRecovery {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
