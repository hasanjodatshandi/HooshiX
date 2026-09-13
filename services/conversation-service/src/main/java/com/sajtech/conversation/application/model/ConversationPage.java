package com.sajtech.conversation.application.model;

import com.sajtech.conversation.domain.Conversation;
import java.util.List;

public record ConversationPage(List<Conversation> conversations, String nextPageToken) {
  public ConversationPage {
    conversations = List.copyOf(conversations);
    nextPageToken = nextPageToken == null ? "" : nextPageToken;
  }
}
