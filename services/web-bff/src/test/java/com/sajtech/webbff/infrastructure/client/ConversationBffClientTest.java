package com.sajtech.webbff.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.protobuf.Timestamp;
import com.sajtech.conversation.contract.v1.*;
import com.sajtech.webbff.application.*;
import io.grpc.*;
import io.grpc.inprocess.*;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConversationBffClientTest {
  private static final Metadata.Key<String> AUTH =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> AUTH_CONTEXT = Context.key("test-conversation-auth");
  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void stop() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  void mapsCreateWithBearerAndBoundedDeadlineWithoutInternalModelData() throws Exception {
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    AtomicReference<CreateConversationRequest> captured = new AtomicReference<>();
    start(
        new ConversationServiceGrpc.ConversationServiceImplBase() {
          @Override
          public void createConversation(
              CreateConversationRequest request,
              StreamObserver<CreateConversationResponse> observer) {
            captured.set(request);
            assertThat(AUTH_CONTEXT.get()).isEqualTo("Bearer exact-audience-token");
            assertThat(Context.current().getDeadline()).isNotNull();
            assertThat(Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS))
                .isBetween(1L, 900L);
            observer.onNext(
                CreateConversationResponse.newBuilder()
                    .setConversation(conversation(conversationId))
                    .build());
            observer.onCompleted();
          }
        });

    var result = new ConversationBffClient(channel).create("exact-audience-token", requestId, "T");

    assertThat(captured.get().getRequestId()).isEqualTo(requestId.toString());
    assertThat(captured.get().getTitle()).isEqualTo("T");
    assertThat(result.conversationId()).isEqualTo(conversationId);
    assertThat(result.lifecycle()).isEqualTo("ACTIVE");
  }

  @Test
  void mapsResourceAndDependencyFailuresWithoutDownstreamDetails() throws Exception {
    start(
        new ConversationServiceGrpc.ConversationServiceImplBase() {
          @Override
          public void getConversation(
              GetConversationRequest request, StreamObserver<GetConversationResponse> observer) {
            observer.onError(
                Status.NOT_FOUND.withDescription("private downstream detail").asRuntimeException());
          }
        });

    assertThatThrownBy(() -> new ConversationBffClient(channel).get("token", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BffException.class,
            failure -> {
              assertThat(failure.error()).isEqualTo(BffError.RESOURCE_NOT_FOUND);
              assertThat(failure.getMessage()).doesNotContain("private downstream detail");
            });
  }

  @Test
  void rejectsInvalidSuccessfulPayloadAsUnavailable() throws Exception {
    start(
        new ConversationServiceGrpc.ConversationServiceImplBase() {
          @Override
          public void getConversation(
              GetConversationRequest request, StreamObserver<GetConversationResponse> observer) {
            observer.onNext(
                GetConversationResponse.newBuilder()
                    .setConversation(
                        conversation(UUID.randomUUID()).toBuilder().setConversationId("bad"))
                    .build());
            observer.onCompleted();
          }
        });

    assertThatThrownBy(() -> new ConversationBffClient(channel).get("token", UUID.randomUUID()))
        .isInstanceOfSatisfying(
            BffException.class,
            failure -> assertThat(failure.error()).isEqualTo(BffError.DEPENDENCY_UNAVAILABLE));
  }

  private void start(ConversationServiceGrpc.ConversationServiceImplBase service) throws Exception {
    String name = InProcessServerBuilder.generateName();
    ServerInterceptor metadata =
        new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            return Contexts.interceptCall(
                Context.current().withValue(AUTH_CONTEXT, headers.get(AUTH)), call, headers, next);
          }
        };
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(service)
            .intercept(metadata)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  private static ConversationView conversation(UUID id) {
    Timestamp now = timestamp(Instant.parse("2026-09-20T12:00:00Z"));
    return ConversationView.newBuilder()
        .setConversationId(id.toString())
        .setTitle("T")
        .setLifecycle(ConversationLifecycle.CONVERSATION_LIFECYCLE_ACTIVE)
        .setVersion(1)
        .setCreatedAt(now)
        .setLastActivityAt(now)
        .build();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }
}
