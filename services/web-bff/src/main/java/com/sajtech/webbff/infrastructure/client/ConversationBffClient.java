package com.sajtech.webbff.infrastructure.client;

import com.google.protobuf.Timestamp;
import com.sajtech.conversation.contract.v1.*;
import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.ConversationGateway;
import com.sajtech.webbff.application.port.out.ConversationGateway.*;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class ConversationBffClient implements ConversationGateway {
  private static final Metadata.Key<String> AUTH =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final long DEADLINE_MILLIS = 900;
  private final ManagedChannel channel;

  public ConversationBffClient(ManagedChannel channel) {
    this.channel = Objects.requireNonNull(channel);
  }

  public ConversationDto create(String token, UUID requestId, String title) {
    try {
      return conversation(
          stub(token)
              .createConversation(
                  CreateConversationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setTitle(title)
                      .build())
              .getConversation());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public ConversationPage list(String token, int pageSize, String pageToken) {
    try {
      var response =
          stub(token)
              .listConversations(
                  ListConversationsRequest.newBuilder()
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      return new ConversationPage(
          response.getConversationsList().stream()
              .map(ConversationBffClient::conversation)
              .toList(),
          response.getNextPageToken());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public ConversationDto get(String token, UUID conversationId) {
    try {
      return conversation(
          stub(token)
              .getConversation(
                  GetConversationRequest.newBuilder()
                      .setConversationId(conversationId.toString())
                      .build())
              .getConversation());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public ConversationDto archive(
      String token, UUID requestId, UUID conversationId, long expectedVersion) {
    try {
      return conversation(
          stub(token)
              .archiveConversation(
                  ArchiveConversationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setConversationId(conversationId.toString())
                      .setExpectedVersion(expectedVersion)
                      .build())
              .getConversation());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public void delete(String token, UUID requestId, UUID conversationId, long expectedVersion) {
    try {
      var response =
          stub(token)
              .deleteConversation(
                  DeleteConversationRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setConversationId(conversationId.toString())
                      .setExpectedVersion(expectedVersion)
                      .build());
      if (!response.getAccepted())
        throw new BffException(
            BffError.DEPENDENCY_UNAVAILABLE, "Conversation returned an invalid delete response");
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public MessagePage messages(String token, UUID conversationId, int pageSize, String pageToken) {
    try {
      var response =
          stub(token)
              .listMessages(
                  ListMessagesRequest.newBuilder()
                      .setConversationId(conversationId.toString())
                      .setPageSize(pageSize)
                      .setPageToken(pageToken == null ? "" : pageToken)
                      .build());
      return new MessagePage(
          response.getMessagesList().stream().map(ConversationBffClient::message).toList(),
          response.getNextPageToken());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public RunDto createRun(String token, UUID requestId, UUID conversationId, String userMessage) {
    try {
      return run(
          stub(token)
              .createModelRun(
                  CreateModelRunRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setConversationId(conversationId.toString())
                      .setUserMessage(userMessage)
                      .build())
              .getRun());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public RunDto getRun(String token, UUID conversationId, UUID runId) {
    try {
      return run(
          stub(token)
              .getModelRun(
                  GetModelRunRequest.newBuilder()
                      .setConversationId(conversationId.toString())
                      .setRunId(runId.toString())
                      .build())
              .getRun());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  public RunDto cancelRun(String token, UUID requestId, UUID conversationId, UUID runId) {
    try {
      return run(
          stub(token)
              .cancelModelRun(
                  CancelModelRunRequest.newBuilder()
                      .setRequestId(requestId.toString())
                      .setConversationId(conversationId.toString())
                      .setRunId(runId.toString())
                      .build())
              .getRun());
    } catch (StatusRuntimeException exception) {
      throw map(exception);
    }
  }

  private ConversationServiceGrpc.ConversationServiceBlockingStub stub(String token) {
    if (token == null || token.isBlank())
      throw new BffException(BffError.DEPENDENCY_UNAVAILABLE, "Conversation token is missing");
    Metadata metadata = new Metadata();
    metadata.put(AUTH, "Bearer " + token);
    return ConversationServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
        .withDeadlineAfter(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
  }

  private static ConversationDto conversation(ConversationView value) {
    return new ConversationDto(
        uuid(value.getConversationId()),
        value.getTitle(),
        trimPrefix(value.getLifecycle().name(), "CONVERSATION_LIFECYCLE_"),
        value.getVersion(),
        instant(value.getCreatedAt()),
        instant(value.getLastActivityAt()));
  }

  private static MessageDto message(MessageView value) {
    return new MessageDto(
        uuid(value.getMessageId()),
        uuid(value.getConversationId()),
        trimPrefix(value.getRole().name(), "MESSAGE_ROLE_"),
        value.getContent(),
        value.getOrdinal(),
        instant(value.getCreatedAt()));
  }

  private static RunDto run(ModelRunView value) {
    return new RunDto(
        uuid(value.getRunId()),
        uuid(value.getConversationId()),
        trimPrefix(value.getState().name(), "MODEL_RUN_STATE_"),
        value.getFailureCode() == ModelRunFailureCode.MODEL_RUN_FAILURE_CODE_UNSPECIFIED
            ? null
            : trimPrefix(value.getFailureCode().name(), "MODEL_RUN_FAILURE_CODE_"),
        value.getCancellationRequested(),
        instant(value.getCreatedAt()),
        value.hasStartedAt() ? instant(value.getStartedAt()) : null,
        value.hasCompletedAt() ? instant(value.getCompletedAt()) : null);
  }

  private static String trimPrefix(String value, String prefix) {
    if (!value.startsWith(prefix) || value.length() == prefix.length())
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Conversation returned an invalid enum");
    return value.substring(prefix.length());
  }

  private static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (id.version() != 4 || !id.toString().equals(value)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException exception) {
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Conversation returned an invalid UUID", exception);
    }
  }

  private static Instant instant(Timestamp value) {
    try {
      return Instant.ofEpochSecond(value.getSeconds(), value.getNanos());
    } catch (RuntimeException exception) {
      throw new BffException(
          BffError.DEPENDENCY_UNAVAILABLE, "Conversation returned an invalid time", exception);
    }
  }

  private static BffException map(StatusRuntimeException exception) {
    return switch (exception.getStatus().getCode()) {
      case UNAUTHENTICATED, PERMISSION_DENIED ->
          new BffException(BffError.AUTHORIZATION_DENIED, "Conversation access denied", exception);
      case INVALID_ARGUMENT ->
          new BffException(BffError.INVALID_REQUEST, "Conversation request is invalid", exception);
      case NOT_FOUND ->
          new BffException(
              BffError.RESOURCE_NOT_FOUND, "Conversation resource was not found", exception);
      case ABORTED, FAILED_PRECONDITION, RESOURCE_EXHAUSTED ->
          new BffException(
              BffError.RESOURCE_CONFLICT,
              "Conversation request conflicts with current state",
              exception);
      default ->
          new BffException(
              BffError.DEPENDENCY_UNAVAILABLE, "Conversation is unavailable", exception);
    };
  }
}
