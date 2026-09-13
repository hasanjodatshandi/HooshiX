package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.ConversationPage;
import com.sajtech.conversation.domain.Conversation;
import java.time.Instant;
import java.util.UUID;

public interface ConversationRepository {
  Conversation create(
      ConversationActor actor, UUID requestId, UUID conversationId, String title, Instant now);

  ConversationPage listOwned(ConversationActor actor, int pageSize, String pageToken);

  Conversation getOwned(ConversationActor actor, UUID conversationId);

  Conversation archiveOwned(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      long expectedVersion,
      Instant now);

  void deleteOwned(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      long expectedVersion,
      Instant now);
}
