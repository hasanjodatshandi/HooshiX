package com.sajtech.conversation.infrastructure.erasure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.contract.v1.BeginParticipantErasureRequest;
import com.sajtech.identity.contract.v1.BeginParticipantErasureResponse;
import com.sajtech.identity.contract.v1.ErasureParticipant;
import com.sajtech.identity.contract.v1.IdentityErasureServiceGrpc;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IdentityErasureTargetClientTest {
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-erasure-caller", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> CALLER_CONTEXT = Context.key("test-erasure-caller");
  private Server server;
  private io.grpc.ManagedChannel channel;

  @AfterEach
  void stop() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  void resolvesOnlyConversationUserTargetWithWorkloadIdentityAndBoundedRequest() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID erasureRequestId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    AtomicReference<BeginParticipantErasureRequest> request = new AtomicReference<>();
    start(
        new IdentityErasureServiceGrpc.IdentityErasureServiceImplBase() {
          @Override
          public void beginParticipantErasure(
              BeginParticipantErasureRequest value,
              StreamObserver<BeginParticipantErasureResponse> observer) {
            assertThat(CALLER_CONTEXT.get()).isEqualTo("conversation-service");
            assertThat(Context.current().getDeadline()).isNotNull();
            request.set(value);
            observer.onNext(validResponse(userId));
            observer.onCompleted();
          }
        });

    assertThat(new IdentityErasureTargetClient(channel).resolve(eventId, erasureRequestId, "2"))
        .isEqualTo(userId);
    assertThat(request.get().getEventId()).isEqualTo(eventId.toString());
    assertThat(request.get().getErasureRequestId()).isEqualTo(erasureRequestId.toString());
    assertThat(request.get().getParticipant())
        .isEqualTo(ErasureParticipant.ERASURE_PARTICIPANT_CONVERSATION_SERVICE);
    assertThat(request.get().getParticipantPolicyVersion()).isEqualTo("2");
  }

  @Test
  void rejectsIncompletePagedMissingAndNotificationTargets() throws Exception {
    UUID userId = UUID.randomUUID();
    BeginParticipantErasureResponse[] invalid = {
      validResponse(userId).toBuilder().setCompletePage(false).build(),
      validResponse(userId).toBuilder().setNextPageToken("unexpected").build(),
      validResponse(userId).toBuilder().clearUserId().build(),
      validResponse(userId).toBuilder().addNotificationIds(UUID.randomUUID().toString()).build()
    };
    AtomicReference<Integer> index = new AtomicReference<>(0);
    start(
        new IdentityErasureServiceGrpc.IdentityErasureServiceImplBase() {
          @Override
          public void beginParticipantErasure(
              BeginParticipantErasureRequest value,
              StreamObserver<BeginParticipantErasureResponse> observer) {
            observer.onNext(invalid[index.get()]);
            observer.onCompleted();
          }
        });

    IdentityErasureTargetClient client = new IdentityErasureTargetClient(channel);
    for (int current = 0; current < invalid.length; current++) {
      index.set(current);
      assertThatThrownBy(() -> client.resolve(UUID.randomUUID(), UUID.randomUUID(), "2"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("Identity returned an invalid Conversation erasure target");
    }
  }

  private void start(IdentityErasureServiceGrpc.IdentityErasureServiceImplBase service)
      throws Exception {
    String name = InProcessServerBuilder.generateName();
    ServerInterceptor caller =
        new ServerInterceptor() {
          @Override
          public <RequestT, ResponseT> ServerCall.Listener<RequestT> interceptCall(
              ServerCall<RequestT, ResponseT> call,
              Metadata headers,
              ServerCallHandler<RequestT, ResponseT> next) {
            return Contexts.interceptCall(
                Context.current().withValue(CALLER_CONTEXT, headers.get(CALLER)),
                call,
                headers,
                next);
          }
        };
    server =
        InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(service)
            .intercept(caller)
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
  }

  private static BeginParticipantErasureResponse validResponse(UUID userId) {
    return BeginParticipantErasureResponse.newBuilder()
        .setParticipant(ErasureParticipant.ERASURE_PARTICIPANT_CONVERSATION_SERVICE)
        .setUserId(userId.toString())
        .setCompletePage(true)
        .build();
  }
}
