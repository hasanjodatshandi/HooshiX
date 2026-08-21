package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import org.slf4j.*;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityKeyRingRefresher {
  private static final Logger LOGGER = LoggerFactory.getLogger(IdentityKeyRingRefresher.class);
  private final FileBackedKeyRing[] keyRings;

  public IdentityKeyRingRefresher(FileBackedKeyRing... keyRings) {
    this.keyRings = keyRings.clone();
  }

  @Scheduled(fixedDelayString = "PT30S")
  public void refresh() {
    for (FileBackedKeyRing keyRing : keyRings) {
      try {
        keyRing.refresh();
      } catch (RuntimeException ignored) {
        LOGGER
            .atWarn()
            .addKeyValue("eventCode", "IDENTITY_KEY_RING_REFRESH_FAILED")
            .log("Identity key-ring refresh failed");
      }
    }
  }
}
