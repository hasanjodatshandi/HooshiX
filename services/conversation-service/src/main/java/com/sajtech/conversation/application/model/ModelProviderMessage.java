package com.sajtech.conversation.application.model;

import com.sajtech.conversation.domain.MessageRole;
import java.util.Objects;

public record ModelProviderMessage(MessageRole role, String content) {
  public ModelProviderMessage {
    Objects.requireNonNull(role);
    Objects.requireNonNull(content);
    if (content.isBlank()
        || content.length() > 65_536
        || content.codePoints().anyMatch(value -> value == 0)) {
      throw new IllegalArgumentException("Model provider message is invalid");
    }
  }
}
