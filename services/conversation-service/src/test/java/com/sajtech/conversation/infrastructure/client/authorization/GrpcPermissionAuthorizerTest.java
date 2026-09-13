package com.sajtech.conversation.infrastructure.client.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.authorization.contract.v1.AuthorizationServiceGrpc;
import com.sajtech.authorization.contract.v1.CheckPermissionRequest;
import com.sajtech.authorization.contract.v1.CheckPermissionResponse;
import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcPermissionAuthorizerTest {
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-authorization-caller", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> CALLER_CONTEXT = Context.key("test-caller");
  private Server server;
  private io.grpc.ManagedChannel channel;

  @AfterEach
  void stop() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  void sendsExactActorPermissionCallerAndBoundedDeadlineOnce() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<CheckPermissionRequest> request = new AtomicReference<>();
    AtomicReference<Long> remainingMillis = new AtomicReference<>();
    start(
        new AuthorizationServiceGrpc.AuthorizationServiceImplBase() {
          @Override
          public void checkPermission(
              CheckPermissionRequest value, StreamObserver<CheckPermissionResponse> observer) {
            calls.incrementAndGet();
            request.set(value);
            assertThat(CALLER_CONTEXT.get()).isEqualTo("conversation-service");
            Deadline deadline = Context.current().getDeadline();
            remainingMillis.set(deadline.timeRemaining(TimeUnit.MILLISECONDS));
            observer.onNext(CheckPermissionResponse.getDefaultInstance());
            observer.onCompleted();
          }
        });
    ConversationActor actor = actor();

    new GrpcPermissionAuthorizer(channel, 2).check(actor, "conversation.read");

    assertThat(calls).hasValue(1);
    assertThat(request.get().getTenantId()).isEqualTo(actor.tenantId().toString());
    assertThat(request.get().getMembershipId()).isEqualTo(actor.membershipId().toString());
    assertThat(request.get().getPermissionKey()).isEqualTo("conversation.read");
    assertThat(remainingMillis.get()).isBetween(1L, GrpcPermissionAuthorizer.DEADLINE_MILLIS);
  }

  @Test
  void denialAndTransportFailureMapFailClosedWithoutRetry() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    start(
        new AuthorizationServiceGrpc.AuthorizationServiceImplBase() {
          @Override
          public void checkPermission(
              CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
            calls.incrementAndGet();
            observer.onError(Status.PERMISSION_DENIED.asRuntimeException());
          }
        });
    var authorizer = new GrpcPermissionAuthorizer(channel, 1);

    assertThatThrownBy(() -> authorizer.check(actor(), "conversation.delete"))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.AUTHORIZATION_DENIED));
    assertThat(calls).hasValue(1);

    channel.shutdownNow();
    assertThatThrownBy(() -> authorizer.check(actor(), "conversation.delete"))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.AUTHORIZATION_UNAVAILABLE));
    assertThat(calls).hasValue(1);
  }

  @Test
  void localConcurrencySaturationRejectsWithoutQueueingOrSecondRpc() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    start(
        new AuthorizationServiceGrpc.AuthorizationServiceImplBase() {
          @Override
          public void checkPermission(
              CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
            calls.incrementAndGet();
            entered.countDown();
            try {
              if (!release.await(200, TimeUnit.MILLISECONDS)) {
                observer.onError(Status.DEADLINE_EXCEEDED.asRuntimeException());
                return;
              }
              observer.onNext(CheckPermissionResponse.getDefaultInstance());
              observer.onCompleted();
            } catch (InterruptedException exception) {
              Thread.currentThread().interrupt();
              observer.onError(Status.CANCELLED.asRuntimeException());
            }
          }
        });
    var authorizer = new GrpcPermissionAuthorizer(channel, 1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> first = executor.submit(() -> authorizer.check(actor(), "conversation.generate"));
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> authorizer.check(actor(), "conversation.generate"))
          .isInstanceOfSatisfying(
              ConversationException.class,
              exception ->
                  assertThat(exception.error())
                      .isEqualTo(ConversationError.AUTHORIZATION_UNAVAILABLE));
      assertThat(calls).hasValue(1);
      release.countDown();
      first.get(1, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  private void start(AuthorizationServiceGrpc.AuthorizationServiceImplBase service)
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

  private static ConversationActor actor() {
    return new ConversationActor(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s".repeat(43));
  }
}
