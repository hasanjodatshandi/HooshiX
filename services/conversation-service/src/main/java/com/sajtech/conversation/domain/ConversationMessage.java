package com.sajtech.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ConversationMessage(
    UUID id,
    UUID conversationId,
    MessageRole role,
    String content,
    long ordinal,
    Instant createdAt) {
  public ConversationMessage {
    Objects.requireNonNull(id);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(role);
    Objects.requireNonNull(content);
    Objects.requireNonNull(createdAt);
    if (content.isEmpty() || ordinal < 1) throw new IllegalArgumentException("Message is invalid");
  }
}
