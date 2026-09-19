package com.sajtech.conversation.application.model;

import com.sajtech.conversation.domain.ConversationMessage;
import java.util.List;
import java.util.Objects;

public record MessagePage(List<ConversationMessage> messages, String nextPageToken) {
  public MessagePage {
    messages = List.copyOf(messages);
    Objects.requireNonNull(nextPageToken);
  }
}
