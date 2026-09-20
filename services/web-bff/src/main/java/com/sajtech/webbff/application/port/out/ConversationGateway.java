package com.sajtech.webbff.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ConversationGateway {
  ConversationDto create(String token, UUID requestId, String title);

  ConversationPage list(String token, int pageSize, String pageToken);

  ConversationDto get(String token, UUID conversationId);

  ConversationDto archive(String token, UUID requestId, UUID conversationId, long expectedVersion);

  void delete(String token, UUID requestId, UUID conversationId, long expectedVersion);

  MessagePage messages(String token, UUID conversationId, int pageSize, String pageToken);

  RunDto createRun(String token, UUID requestId, UUID conversationId, String userMessage);

  RunDto getRun(String token, UUID conversationId, UUID runId);

  RunDto cancelRun(String token, UUID requestId, UUID conversationId, UUID runId);

  void submitFeedback(
      String token, UUID requestId, UUID conversationId, UUID runId, RunFeedbackValue value);

  enum RunFeedbackValue {
    HELPFUL,
    NOT_HELPFUL,
    UNSAFE,
    FACTUALLY_WRONG
  }

  record ConversationDto(
      UUID conversationId,
      String title,
      String lifecycle,
      long version,
      Instant createdAt,
      Instant lastActivityAt) {}

  record ConversationPage(List<ConversationDto> conversations, String nextPageToken) {
    public ConversationPage {
      conversations = List.copyOf(conversations);
    }
  }

  record MessageDto(
      UUID messageId,
      UUID conversationId,
      String role,
      String content,
      long ordinal,
      Instant createdAt) {}

  record MessagePage(List<MessageDto> messages, String nextPageToken) {
    public MessagePage {
      messages = List.copyOf(messages);
    }
  }

  record RunDto(
      UUID runId,
      UUID conversationId,
      String state,
      String failureCode,
      boolean cancellationRequested,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {}
}
