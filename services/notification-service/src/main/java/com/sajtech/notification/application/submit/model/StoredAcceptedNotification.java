package com.sajtech.notification.application.submit.model;

import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

public record StoredAcceptedNotification(
    UUID notificationId,
    byte[] fingerprint,
    String fingerprintVersion,
    String fingerprintKeyId,
    NotificationLifecycle lifecycle,
    Instant acceptedAt) {
  public StoredAcceptedNotification {
    fingerprint = Arrays.copyOf(fingerprint, fingerprint.length);
  }

  @Override
  public byte[] fingerprint() {
    return Arrays.copyOf(fingerprint, fingerprint.length);
  }
}
