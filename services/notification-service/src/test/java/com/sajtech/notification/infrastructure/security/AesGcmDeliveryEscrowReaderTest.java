package com.sajtech.notification.infrastructure.security;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.submit.model.*;
import com.sajtech.notification.application.template.model.*;
import com.sajtech.notification.domain.notification.model.*;
import com.sajtech.notification.infrastructure.security.escrow.*;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AesGcmDeliveryEscrowReaderTest {
  @TempDir Path temp;

  @Test
  void roundTripsExactAcceptedPayloadAndRejectsCiphertextTamper() throws Exception {
    Path keysPath = temp.resolve("delivery.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 23);
    Files.writeString(
        keysPath,
        "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n",
        StandardCharsets.UTF_8);
    Clock clock = Clock.systemUTC();
    FileBackedKeyRing keys = new FileBackedKeyRing(keysPath, "AES", 32, clock, Duration.ofHours(1));
    UUID notificationId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    CanonicalNotificationIntent intent =
        new CanonicalNotificationIntent(
            requestId,
            "identity-service",
            NotificationChannel.EMAIL,
            "person@example.com",
            "en",
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            new VerificationCodeContent(
                NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, "12345678", 10),
            Instant.now().plusSeconds(600));
    NotificationTemplateVersion template =
        new NotificationTemplateVersion(
            templateId,
            NotificationChannel.EMAIL,
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            "en",
            "0".repeat(64),
            "subject",
            "text",
            "html");
    RenderedNotification rendered =
        new RenderedNotification("subject", "secret text", "<p>secret</p>");
    EncryptedDeliveryPayload encrypted =
        new AesGcmDeliveryEscrow(keys, new SecureRandom())
            .encrypt(notificationId, intent, template, rendered);
    DeliveryEscrowEnvelope envelope = envelope(notificationId, intent, templateId, encrypted);
    AesGcmDeliveryEscrowReader reader = new AesGcmDeliveryEscrowReader(keys);

    assertThat(reader.decrypt(envelope))
        .isEqualTo(
            new DecryptedDeliveryPayload(
                "person@example.com", "subject", "secret text", "<p>secret</p>"));

    byte[] tampered = encrypted.text().ciphertext();
    tampered[0] ^= 1;
    DeliveryEscrowEnvelope modified =
        new DeliveryEscrowEnvelope(
            envelope.notificationId(),
            envelope.callerService(),
            envelope.requestId(),
            envelope.channel(),
            envelope.semanticType(),
            envelope.templateVersionId(),
            envelope.formatVersion(),
            envelope.keyId(),
            envelope.recipient(),
            envelope.subject(),
            new DeliveryCiphertext(encrypted.text().nonce(), tampered),
            envelope.html());
    assertThatThrownBy(() -> reader.decrypt(modified))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Unable to decrypt notification delivery escrow");
  }

  private static DeliveryEscrowEnvelope envelope(
      UUID notificationId,
      CanonicalNotificationIntent intent,
      UUID templateId,
      EncryptedDeliveryPayload encrypted) {
    return new DeliveryEscrowEnvelope(
        notificationId,
        intent.callerService(),
        intent.requestId(),
        intent.channel(),
        intent.semanticType(),
        templateId,
        encrypted.formatVersion(),
        encrypted.keyId(),
        field(encrypted.recipient()),
        field(encrypted.subject()),
        field(encrypted.text()),
        field(encrypted.html()));
  }

  private static DeliveryCiphertext field(EncryptedField field) {
    return field == null ? null : new DeliveryCiphertext(field.nonce(), field.ciphertext());
  }
}
