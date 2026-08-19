package com.sajtech.identity.infrastructure.observability;

import com.sajtech.identity.infrastructure.security.jwt.FileBackedRsaSigningKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class IdentityAuthenticationKeyRingRefresher {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(IdentityAuthenticationKeyRingRefresher.class);
  private final FileBackedKeyRing refreshKeys;
  private final FileBackedRsaSigningKeyRing signingKeys;

  public IdentityAuthenticationKeyRingRefresher(
      FileBackedKeyRing refreshKeys, FileBackedRsaSigningKeyRing signingKeys) {
    this.refreshKeys = refreshKeys;
    this.signingKeys = signingKeys;
  }

  @Scheduled(fixedDelayString = "PT30S")
  public void refresh() {
    refreshSymmetric();
    refreshSigning();
  }

  private void refreshSymmetric() {
    try {
      refreshKeys.refresh();
    } catch (RuntimeException ignored) {
      warn("IDENTITY_REFRESH_KEY_RING_REFRESH_FAILED", "Identity refresh key-ring refresh failed");
    }
  }

  private void refreshSigning() {
    try {
      signingKeys.refresh();
    } catch (RuntimeException ignored) {
      warn("IDENTITY_JWT_KEY_RING_REFRESH_FAILED", "Identity JWT key-ring refresh failed");
    }
  }

  private static void warn(String eventCode, String message) {
    LOGGER.atWarn().addKeyValue("eventCode", eventCode).log(message);
  }
}
