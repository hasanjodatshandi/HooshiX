package com.sajtech.identity.application.registration;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CanonicalIntent {
  private CanonicalIntent() {}

  static byte[] encode(String... fields) {
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (DataOutputStream data = new DataOutputStream(output)) {
        for (String field : fields) {
          byte[] value = field == null ? new byte[0] : field.getBytes(StandardCharsets.UTF_8);
          data.writeInt(value.length);
          data.write(value);
        }
      }
      return output.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException("in-memory canonical encoding failed", impossible);
    }
  }
}
