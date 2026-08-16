package com.sajtech.notification.application.template.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TemplateContentDigest {
  private static final String DOMAIN = "notification-template-content-v1";

  public String compute(String subject, String text, String html) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(encode(subject, text, html));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public boolean matches(String expectedDigest, String subject, String text, String html) {
    if (expectedDigest == null || !expectedDigest.matches("[0-9a-f]{64}")) {
      return false;
    }
    byte[] expected = HexFormat.of().parseHex(expectedDigest);
    byte[] actual = HexFormat.of().parseHex(compute(subject, text, html));
    return MessageDigest.isEqual(expected, actual);
  }

  private static byte[] encode(String subject, String text, String html) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        write(output, DOMAIN);
        writeNullable(output, subject);
        writeNullable(output, text);
        writeNullable(output, html);
      }
      return bytes.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("Unable to encode template content", impossible);
    }
  }

  private static void write(DataOutputStream output, String value) throws IOException {
    byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
    output.writeInt(encoded.length);
    output.write(encoded);
  }

  private static void writeNullable(DataOutputStream output, String value) throws IOException {
    if (value == null) {
      output.writeInt(-1);
      return;
    }
    write(output, value);
  }
}
