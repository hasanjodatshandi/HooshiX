package com.sajtech.identity.application.notification.model;

import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Instant;
import java.util.UUID;

public record NotificationOutboxRecord(
    UUID outboxId,
    UUID requestId,
    RegistrationChannel channel,
    RegistrationLocale locale,
    String escrowKeyId,
    byte[] nonce,
    byte[] ciphertext,
    Instant messageNotAfter,
    int attemptCount) {
  public NotificationOutboxRecord {
    nonce = nonce.clone();
    ciphertext = ciphertext.clone();
  }

  @Override
  public byte[] nonce() {
    return nonce.clone();
  }

  @Override
  public byte[] ciphertext() {
    return ciphertext.clone();
  }
}
