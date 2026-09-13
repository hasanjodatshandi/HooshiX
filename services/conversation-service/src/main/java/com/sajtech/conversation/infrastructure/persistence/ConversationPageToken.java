package com.sajtech.conversation.infrastructure.persistence;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

final class ConversationPageToken {
  private static final int BYTES = Long.BYTES + Integer.BYTES + (2 * Long.BYTES);

  private ConversationPageToken() {}

  static String encode(Instant activity, UUID id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            ByteBuffer.allocate(BYTES)
                .putLong(activity.getEpochSecond())
                .putInt(activity.getNano())
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array());
  }

  static Cursor decode(String token) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(token);
      if (bytes.length != BYTES) throw new IllegalArgumentException("Invalid page token");
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      Instant activity = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
      UUID id = new UUID(buffer.getLong(), buffer.getLong());
      return new Cursor(activity, id);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Invalid page token", exception);
    }
  }

  record Cursor(Instant activity, UUID id) {}
}
