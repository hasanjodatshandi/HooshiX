package com.sajtech.notification.infrastructure.security.escrow;

import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.EncryptedField;
import com.sajtech.notification.application.submit.port.out.DeliveryEscrowPort;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.model.RenderedNotification;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.notification.infrastructure.security.keyring.KeyRingMaterial;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmDeliveryEscrow implements DeliveryEscrowPort {
  private static final int FORMAT_VERSION = 1;
  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final FileBackedKeyRing keyRing;
  private final SecureRandom random;

  public AesGcmDeliveryEscrow(FileBackedKeyRing keyRing, SecureRandom random) {
    this.keyRing = keyRing;
    this.random = random;
  }

  @Override
  public EncryptedDeliveryPayload encrypt(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      RenderedNotification rendered) {
    KeyRingMaterial active = keyRing.activeKey();
    return new EncryptedDeliveryPayload(
        FORMAT_VERSION,
        active.keyId(),
        encryptField(
            active, notificationId, intent, template, "recipient", intent.canonicalRecipient()),
        encryptOptional(active, notificationId, intent, template, "subject", rendered.subject()),
        encryptField(active, notificationId, intent, template, "text", rendered.text()),
        encryptOptional(active, notificationId, intent, template, "html", rendered.html()));
  }

  private EncryptedField encryptOptional(
      KeyRingMaterial active,
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      String field,
      String plaintext) {
    if (plaintext == null) {
      return null;
    }
    return encryptField(active, notificationId, intent, template, field, plaintext);
  }

  private EncryptedField encryptField(
      KeyRingMaterial active,
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      String field,
      String plaintext) {
    byte[] nonce = new byte[NONCE_BYTES];
    random.nextBytes(nonce);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, active.key(), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(aad(notificationId, intent, template, active.keyId(), field));
      return new EncryptedField(nonce, cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to encrypt notification delivery escrow", exception);
    }
  }

  private static byte[] aad(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      NotificationTemplateVersion template,
      String keyId,
      String field) {
    return DeliveryEscrowAad.encode(
        FORMAT_VERSION,
        keyId,
        notificationId,
        intent.callerService(),
        intent.requestId(),
        intent.channel(),
        intent.semanticType(),
        template.versionId(),
        field);
  }
}
