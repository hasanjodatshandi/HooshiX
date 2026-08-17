package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

public final class FingerprintMaterialEncoder {
  public byte[] encode(CanonicalNotificationIntent intent) {
    var out = new ByteArrayOutputStream();
    write(out, intent.callerService());
    write(out, intent.requestId().toString());
    write(out, intent.channel().name());
    write(out, intent.recipient());
    write(out, intent.locale());
    write(out, encodeInstant(intent.messageNotAfter()));
    write(out, intent.semanticType().name());
    intent.semanticContent().templateVariables().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              write(out, entry.getKey());
              write(out, entry.getValue());
            });
    return out.toByteArray();
  }

  private static String encodeInstant(Instant instant) {
    return instant == null ? "" : instant.toString();
  }

  private static void write(ByteArrayOutputStream out, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    out.writeBytes(bytes);
  }
}
