package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.MessagePage;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.domain.ModelRun;
import java.time.Instant;
import java.util.UUID;

public interface ModelRunRepository {
  ModelRun findAcceptedReplay(
      ConversationActor actor, UUID requestId, UUID conversationId, String userMessage);

  ModelRun accept(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID messageId,
      UUID runId,
      String userMessage,
      ModelExecutionPolicy policy,
      Instant now);

  MessagePage listMessagesOwned(
      ConversationActor actor, UUID conversationId, int pageSize, String pageToken);

  ModelRun getOwned(ConversationActor actor, UUID conversationId, UUID runId);

  ModelRun cancelOwned(
      ConversationActor actor, UUID requestId, UUID conversationId, UUID runId, Instant now);

  void submitFeedback(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID runId,
      com.sajtech.conversation.application.model.RunFeedbackValue value,
      Instant now);
}
