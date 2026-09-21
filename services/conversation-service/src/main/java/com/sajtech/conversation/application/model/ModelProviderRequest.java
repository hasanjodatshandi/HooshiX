package com.sajtech.conversation.application.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ModelProviderRequest(
    UUID runId, ModelExecutionPolicy policy, List<ModelProviderMessage> messages) {
  public ModelProviderRequest {
    Objects.requireNonNull(runId);
    if (runId.version() != 4 || runId.variant() != 2) {
      throw new IllegalArgumentException("Model provider run identity is invalid");
    }
    Objects.requireNonNull(policy);
    messages = List.copyOf(Objects.requireNonNull(messages));
    if (messages.isEmpty() || messages.size() > 100) {
      throw new IllegalArgumentException("Model provider context is invalid");
    }
  }
}
