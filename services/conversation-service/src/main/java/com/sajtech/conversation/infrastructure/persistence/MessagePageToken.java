package com.sajtech.conversation.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.util.Base64;
import java.util.UUID;

final class MessagePageToken {
  private static final int BYTES = Long.BYTES + (2 * Long.BYTES);

  private MessagePageToken() {}

  static String encode(long ordinal, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ByteBuffer.allocate(BYTES)
                .putLong(ordinal)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
  }

  static Cursor decode(String token) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token);
      if (bytes.length != BYTES) throw new IllegalArgumentException("Invalid page token");
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      long ordinal = buffer.getLong();
      UUID id = new UUID(buffer.getLong(), buffer.getLong());
      if (ordinal < 1 || id.version() != 4 || id.variant() != 2) {
        throw new IllegalArgumentException("Invalid page token");
      }
      return new Cursor(ordinal, id);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Invalid page token", exception);
    }
  }

  record Cursor(long ordinal, UUID id) {}
}
