package com.sajtech.conversation.interfaces.grpc;

import com.google.protobuf.Timestamp;
import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.service.ConversationService;
import com.sajtech.conversation.contract.v1.*;
import com.sajtech.conversation.domain.Conversation;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class ConversationGrpcService
    extends ConversationServiceGrpc.ConversationServiceImplBase {
  private final ConversationService conversations;

  public ConversationGrpcService(ConversationService conversations) {
    this.conversations = Objects.requireNonNull(conversations);
  }

  @Override
  public void createConversation(
      CreateConversationRequest request, StreamObserver<CreateConversationResponse> observer) {
    respond(
        observer,
        () ->
            CreateConversationResponse.newBuilder()
                .setConversation(
                    view(
                        conversations.create(
                            token(), UUID.fromString(request.getRequestId()), request.getTitle())))
                .build());
  }

  @Override
  public void listConversations(
      ListConversationsRequest request, StreamObserver<ListConversationsResponse> observer) {
    respond(
        observer,
        () -> {
          var page = conversations.list(token(), request.getPageSize(), request.getPageToken());
          var response =
              ListConversationsResponse.newBuilder().setNextPageToken(page.nextPageToken());
          page.conversations().forEach(value -> response.addConversations(view(value)));
          return response.build();
        });
  }

  @Override
  public void getConversation(
      GetConversationRequest request, StreamObserver<GetConversationResponse> observer) {
    respond(
        observer,
        () ->
            GetConversationResponse.newBuilder()
                .setConversation(
                    view(conversations.get(token(), UUID.fromString(request.getConversationId()))))
                .build());
  }

  @Override
  public void archiveConversation(
      ArchiveConversationRequest request, StreamObserver<ArchiveConversationResponse> observer) {
    respond(
        observer,
        () ->
            ArchiveConversationResponse.newBuilder()
                .setConversation(
                    view(
                        conversations.archive(
                            token(),
                            UUID.fromString(request.getRequestId()),
                            UUID.fromString(request.getConversationId()),
                            request.getExpectedVersion())))
                .build());
  }

  @Override
  public void deleteConversation(
      DeleteConversationRequest request, StreamObserver<DeleteConversationResponse> observer) {
    respond(
        observer,
        () -> {
          conversations.delete(
              token(),
              UUID.fromString(request.getRequestId()),
              UUID.fromString(request.getConversationId()),
              request.getExpectedVersion());
          return DeleteConversationResponse.newBuilder().setAccepted(true).build();
        });
  }

  private static String token() {
    String value = BearerTokenServerInterceptor.ACCESS_TOKEN.get();
    if (value == null) {
      throw new ConversationException(
          ConversationError.INVALID_ACCESS_TOKEN, "Access token is unavailable");
    }
    return value;
  }

  private static ConversationView view(Conversation value) {
    return ConversationView.newBuilder()
        .setConversationId(value.id().toString())
        .setTitle(value.title())
        .setLifecycle(
            switch (value.lifecycle()) {
              case ACTIVE ->
                  com.sajtech.conversation.contract.v1.ConversationLifecycle
                      .CONVERSATION_LIFECYCLE_ACTIVE;
              case ARCHIVED ->
                  com.sajtech.conversation.contract.v1.ConversationLifecycle
                      .CONVERSATION_LIFECYCLE_ARCHIVED;
              case DELETED ->
                  com.sajtech.conversation.contract.v1.ConversationLifecycle
                      .CONVERSATION_LIFECYCLE_DELETED;
            })
        .setVersion(value.version())
        .setCreatedAt(timestamp(value.createdAt()))
        .setLastActivityAt(timestamp(value.lastActivityAt()))
        .build();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static <T> void respond(StreamObserver<T> observer, Response<T> response) {
    try {
      observer.onNext(response.get());
      observer.onCompleted();
    } catch (ConversationException exception) {
      observer.onError(
          status(exception.error()).withDescription(exception.error().name()).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_REQUEST").asRuntimeException());
    } catch (RuntimeException exception) {
      observer.onError(Status.INTERNAL.withDescription("CONVERSATION_FAILED").asRuntimeException());
    }
  }

  private static Status status(ConversationError error) {
    return switch (error) {
      case INVALID_REQUEST -> Status.INVALID_ARGUMENT;
      case INVALID_ACCESS_TOKEN -> Status.UNAUTHENTICATED;
      case AUTHORIZATION_DENIED -> Status.PERMISSION_DENIED;
      case AUTHORIZATION_UNAVAILABLE, PERSISTENCE_UNAVAILABLE -> Status.UNAVAILABLE;
      case CONVERSATION_NOT_FOUND -> Status.NOT_FOUND;
      case CONVERSATION_CONFLICT -> Status.ABORTED;
      case CONVERSATION_INVALID_STATE -> Status.FAILED_PRECONDITION;
    };
  }

  @FunctionalInterface
  private interface Response<T> {
    T get();
  }
}
