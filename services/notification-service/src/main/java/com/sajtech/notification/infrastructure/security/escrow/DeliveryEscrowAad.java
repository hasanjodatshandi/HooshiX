package com.sajtech.notification.infrastructure.security.escrow;

import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class DeliveryEscrowAad {
  private static final String PURPOSE = "notification-delivery-escrow-local";

  private DeliveryEscrowAad() {}

  static byte[] encode(
      int formatVersion,
      String keyId,
      UUID notificationId,
      String callerService,
      UUID requestId,
      NotificationChannel channel,
      NotificationSemanticType semanticType,
      UUID templateVersionId,
      String field) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, PURPOSE);
        write(output, Integer.toString(formatVersion));
        write(output, keyId);
        write(output, notificationId.toString());
        write(output, callerService);
        write(output, requestId.toString());
        write(output, channel.name());
        write(output, semanticType.name());
        write(output, templateVersionId.toString());
        write(output, field);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode notification escrow AAD", impossible);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }
}
