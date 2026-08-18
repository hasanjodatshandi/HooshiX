package com.sajtech.identity.application.registration;

import com.sajtech.identity.domain.registration.ContactKind;
import com.sajtech.identity.domain.registration.RegistrationLocale;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;

public record OutboxPayload(
    ContactKind contactKind,
    String recipient,
    RegistrationLocale locale,
    String verificationCode,
    Instant messageNotAfter) {
  private static final int FORMAT_VERSION = 1;
  private static final int MAX_TEXT_BYTES = 1024;

  public OutboxPayload {
    Objects.requireNonNull(contactKind, "contactKind");
    Objects.requireNonNull(recipient, "recipient");
    Objects.requireNonNull(locale, "locale");
    Objects.requireNonNull(verificationCode, "verificationCode");
    Objects.requireNonNull(messageNotAfter, "messageNotAfter");
  }

  public byte[] encode() {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(output)) {
        data.writeInt(FORMAT_VERSION);
        writeText(data, contactKind.name());
        writeText(data, recipient);
        writeText(data, locale.wireValue());
        writeText(data, verificationCode);
        data.writeLong(messageNotAfter.getEpochSecond());
        data.writeInt(messageNotAfter.getNano());
      }
      return output.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("in-memory escrow encoding failed", impossible);
    }
  }

  public static OutboxPayload decode(byte[] bytes) {
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
      if (input.readInt() != FORMAT_VERSION) {
        throw new IllegalArgumentException("unsupported caller escrow format");
      }
      ContactKind kind = ContactKind.valueOf(readText(input));
      String recipient = readText(input);
      RegistrationLocale locale = switch (readText(input)) {
        case "fa" -> RegistrationLocale.FA;
        case "en" -> RegistrationLocale.EN;
        default -> throw new IllegalArgumentException("unsupported caller escrow locale");
      };
      String code = readText(input);
      Instant notAfter = Instant.ofEpochSecond(input.readLong(), input.readInt());
      if (input.available() != 0 || !code.matches("[0-9]{8}")) {
        throw new IllegalArgumentException("corrupt caller escrow");
      }
      return new OutboxPayload(kind, recipient, locale, code, notAfter);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalArgumentException("corrupt caller escrow", exception);
    }
  }

  private static void writeText(DataOutputStream output, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_TEXT_BYTES) {
      throw new IllegalArgumentException("caller escrow field exceeds bound");
    }
    output.writeInt(bytes.length);
    output.write(bytes);
  }

  private static String readText(DataInputStream input) throws IOException {
    int length = input.readInt();
    if (length < 0 || length > MAX_TEXT_BYTES) {
      throw new IllegalArgumentException("caller escrow field exceeds bound");
    }
    return new String(input.readNBytes(length), StandardCharsets.UTF_8);
  }
}
