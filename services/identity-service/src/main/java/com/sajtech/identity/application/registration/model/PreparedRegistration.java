package com.sajtech.identity.application.registration.model;

import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import com.sajtech.identity.domain.registration.valueobject.RegistrationProfile;
import java.time.Instant;
import java.util.UUID;

public record PreparedRegistration(
    UUID userId,
    UUID contactId,
    UUID challengeId,
    UUID outboxId,
    UUID notificationRequestId,
    CanonicalContact contact,
    RegistrationProfile profile,
    String passwordHash,
    RegistrationLocale locale,
    byte[] challengeVerifier,
    String challengeKeyId,
    EncryptedHandoff handoff,
    Instant createdAt,
    Instant challengeExpiresAt) {
  public PreparedRegistration {
    challengeVerifier = challengeVerifier.clone();
  }

  @Override
  public byte[] challengeVerifier() {
    return challengeVerifier.clone();
  }
}
