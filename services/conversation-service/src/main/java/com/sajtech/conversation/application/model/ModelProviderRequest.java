package com.sajtech.conversation.application.model;

import java.util.List;
import java.util.Objects;

public record ModelProviderRequest(
    ModelExecutionPolicy policy, List<ModelProviderMessage> messages) {
  public ModelProviderRequest {
    Objects.requireNonNull(policy);
    messages = List.copyOf(Objects.requireNonNull(messages));
    if (messages.isEmpty() || messages.size() > 100) {
      throw new IllegalArgumentException("Model provider context is invalid");
    }
  }
}
