package com.sajtech.notification.infrastructure.security.escrow;

import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.EncryptedField;
import com.sajtech.notification.application.submit.port.out.DeliveryEscrowPort;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import com.sajtech.notification.infrastructure.security.keyring.DeliveryEncryptionKey;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmDeliveryEscrow implements DeliveryEscrowPort {
  private static final int FORMAT_VERSION = 1;
  private static final int NONCE_LENGTH = 12;
  private static final int TAG_BITS = 128;

  private final FileBackedKeyRing keyRing;
  private final SecureRandom secureRandom;

  public AesGcmDeliveryEscrow(FileBackedKeyRing keyRing) {
    this(keyRing, new SecureRandom());
  }

  AesGcmDeliveryEscrow(FileBackedKeyRing keyRing, SecureRandom secureRandom) {
    this.keyRing = keyRing;
    this.secureRandom = secureRandom;
  }

  @Override
  public EncryptedDeliveryPayload encrypt(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      RenderedNotification rendered) {
    if (!keyRing.isFresh()) {
      throw new IllegalStateException("Notification delivery key ring is stale");
    }
    DeliveryEncryptionKey key = keyRing.activeDeliveryKey();
    byte[] associatedData = associatedData(notificationId, intent, template);
    return new EncryptedDeliveryPayload(
        FORMAT_VERSION,
        key.keyId(),
        encryptField(key, associatedData, intent.recipient()),
        encryptField(key, associatedData, rendered.subject()),
        encryptField(key, associatedData, rendered.text()),
        encryptField(key, associatedData, rendered.html()));
  }

  private EncryptedField encryptField(
      DeliveryEncryptionKey key, byte[] associatedData, String plaintext) {
    if (plaintext == null) {
      return null;
    }
    byte[] nonce = new byte[NONCE_LENGTH];
    secureRandom.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key.keyBytes(), "AES"),
          new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(associatedData);
      return new EncryptedField(nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException encryptionFailure) {
      throw new IllegalStateException(
          "Notification delivery payload encryption failed", encryptionFailure);
    }
  }

  private static byte[] associatedData(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template) {
    String value =
        notificationId
            + "\n"
            + intent.callerService()
            + "\n"
            + intent.requestId()
            + "\n"
            + intent.channel().name()
            + "\n"
            + intent.semanticType().name()
            + "\n"
            + template.templateId()
            + "\n"
            + template.version();
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
