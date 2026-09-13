package com.sajtech.conversation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Conversation(
    UUID id,
    UUID tenantId,
    UUID ownerMembershipId,
    String title,
    ConversationLifecycle lifecycle,
    long version,
    Instant createdAt,
    Instant lastActivityAt) {
  public Conversation {
    Objects.requireNonNull(id);
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(ownerMembershipId);
    Objects.requireNonNull(title);
    Objects.requireNonNull(lifecycle);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(lastActivityAt);
    if (version < 1 || lastActivityAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("Conversation state is invalid");
    }
  }
}
