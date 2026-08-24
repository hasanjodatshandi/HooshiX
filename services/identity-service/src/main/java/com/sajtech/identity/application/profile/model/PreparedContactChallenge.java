package com.sajtech.identity.application.profile.model;

import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Instant;
import java.util.UUID;

public record PreparedContactChallenge(
    UUID contactId,
    UUID challengeId,
    UUID outboxId,
    UUID notificationRequestId,
    CanonicalContact contact,
    RegistrationLocale locale,
    byte[] verifier,
    String verifierKeyId,
    EncryptedHandoff handoff,
    Instant now,
    Instant expiresAt) {
  public PreparedContactChallenge {
    verifier = verifier.clone();
  }

  @Override
  public byte[] verifier() {
    return verifier.clone();
  }
}
