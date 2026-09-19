package com.sajtech.conversation.application.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ClaimedModelRun(
    UUID tenantId,
    UUID membershipId,
    UUID conversationId,
    UUID runId,
    List<ModelProviderMessage> messages) {
  public ClaimedModelRun {
    Objects.requireNonNull(tenantId);
    Objects.requireNonNull(membershipId);
    Objects.requireNonNull(conversationId);
    Objects.requireNonNull(runId);
    messages = List.copyOf(Objects.requireNonNull(messages));
    if (messages.isEmpty() || messages.size() > 100) {
      throw new IllegalArgumentException("Claimed model context is invalid");
    }
  }
}
