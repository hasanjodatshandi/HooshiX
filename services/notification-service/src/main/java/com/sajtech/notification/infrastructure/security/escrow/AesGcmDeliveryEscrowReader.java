package com.sajtech.notification.infrastructure.security.escrow;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.out.DeliveryEscrowReaderPort;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

public final class AesGcmDeliveryEscrowReader implements DeliveryEscrowReaderPort {
  private static final int TAG_BITS = 128;
  private final FileBackedKeyRing keyRing;

  public AesGcmDeliveryEscrowReader(FileBackedKeyRing keyRing) {
    this.keyRing = keyRing;
  }

  @Override
  public DecryptedDeliveryPayload decrypt(DeliveryEscrowEnvelope envelope) {
    return new DecryptedDeliveryPayload(
        decryptField(envelope, "recipient", envelope.recipient()),
        decryptOptional(envelope, "subject", envelope.subject()),
        decryptField(envelope, "text", envelope.text()),
        decryptOptional(envelope, "html", envelope.html()));
  }

  private String decryptOptional(
      DeliveryEscrowEnvelope envelope, String field, DeliveryCiphertext encrypted) {
    return encrypted == null ? null : decryptField(envelope, field, encrypted);
  }

  private String decryptField(
      DeliveryEscrowEnvelope envelope, String field, DeliveryCiphertext encrypted) {
    byte[] plaintext = null;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          keyRing.key(envelope.keyId()),
          new GCMParameterSpec(TAG_BITS, encrypted.nonce()));
      cipher.updateAAD(
          DeliveryEscrowAad.encode(
              envelope.formatVersion(),
              envelope.keyId(),
              envelope.notificationId(),
              envelope.callerService(),
              envelope.requestId(),
              envelope.channel(),
              envelope.semanticType(),
              envelope.templateVersionId(),
              field));
      plaintext = cipher.doFinal(encrypted.ciphertext());
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to decrypt notification delivery escrow", exception);
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
    }
  }
}
