package com.sajtech.notification.infrastructure.observability;

import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class NotificationKeyRingRefresher {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationKeyRingRefresher.class);

  private final FileBackedKeyRing fingerprint;
  private final FileBackedKeyRing delivery;

  public NotificationKeyRingRefresher(FileBackedKeyRing fingerprint, FileBackedKeyRing delivery) {
    this.fingerprint = fingerprint;
    this.delivery = delivery;
  }

  @Scheduled(fixedDelayString = "PT30S")
  public void refresh() {
    refreshOne(fingerprint, "NOTIFICATION_FINGERPRINT_KEY_RING_REFRESH_FAILED");
    refreshOne(delivery, "NOTIFICATION_DELIVERY_KEY_RING_REFRESH_FAILED");
  }

  private static void refreshOne(FileBackedKeyRing keyRing, String eventCode) {
    try {
      keyRing.refresh();
    } catch (RuntimeException ignored) {
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", eventCode)
          .log("Notification key-ring refresh failed");
    }
  }
}
