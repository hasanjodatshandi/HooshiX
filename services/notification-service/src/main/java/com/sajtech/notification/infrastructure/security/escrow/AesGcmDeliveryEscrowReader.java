package com.sajtech.notification.infrastructure.security.escrow;

import com.sajtech.notification.application.delivery.model.DecryptedDeliveryPayload;
import com.sajtech.notification.application.delivery.port.out.DeliveryEscrowReaderPort;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmDeliveryEscrowReader implements DeliveryEscrowReaderPort {
  private final FileBackedKeyRing keyRing;

  public AesGcmDeliveryEscrowReader(FileBackedKeyRing keyRing) {
    this.keyRing = keyRing;
  }

  @Override
  public DecryptedDeliveryPayload decrypt(UUID notificationId, UUID attemptId) {
    throw new UnsupportedOperationException("Encrypted payload loading is handled by the delivery repository adapter");
  }
}
