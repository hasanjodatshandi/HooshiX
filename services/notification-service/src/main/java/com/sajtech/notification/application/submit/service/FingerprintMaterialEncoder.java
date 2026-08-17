package com.sajtech.notification.application.submit.service;

import com.sajtech.notification.application.submit.model.CanonicalNotificationIntent;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class FingerprintMaterialEncoder {
  private static final String DOMAIN = "notification-intent-fingerprint-v1";

  public byte[] encode(CanonicalNotificationIntent intent) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        write(output, intent.callerService());
        write(output, intent.channel().name());
        write(output, intent.semanticType().name());
        write(output, intent.canonicalRecipient());
        write(output, intent.locale());
        write(output, canonicalTimestamp(intent.messageNotAfter()));
        if (intent.semanticContent() instanceof VerificationCodeContent verification) {
          write(output, "code");
          write(output, verification.code());
          write(output, "expires_minutes");
          write(output, Integer.toString(verification.expiresMinutes()));
        } else {
          write(output, "no_parameters");
        }
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode notification intent", impossible);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static String canonicalTimestamp(Instant instant) {
    if (instant == null) {
      return "";
    }
    long micros = Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000L), instant.getNano() / 1_000L);
    return Long.toString(micros);
  }
}
