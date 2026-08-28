package com.sajtech.identity.interfaces.observability.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import com.sajtech.identity.contract.v1.IdentityNotificationResultServiceGrpc;
import com.sajtech.identity.contract.v1.ReportNotificationResultRequest;
import com.sajtech.identity.contract.v1.ReportNotificationResultResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TransactionFailureServerInterceptorTest {
  @Test
  void mapsExceptionThroughActualUnaryServerCallChain() throws IOException, InterruptedException {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .intercept(new TransactionFailureServerInterceptor(meters))
            .addService(
                new IdentityNotificationResultServiceGrpc
                    .IdentityNotificationResultServiceImplBase() {
                  @Override
                  public void reportNotificationResult(
                      ReportNotificationResultRequest request,
                      StreamObserver<ReportNotificationResultResponse> observer) {
                    throw new TransactionUnavailableException(
                        TransactionFailure.LOCK_TIMEOUT,
                        new IllegalStateException("sensitive database detail"));
                  }
                })
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  IdentityNotificationResultServiceGrpc.newBlockingStub(channel)
                      .reportNotificationResult(
                          ReportNotificationResultRequest.getDefaultInstance()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              failure -> {
                Status status = ((StatusRuntimeException) failure).getStatus();
                assertThat(status.getCode()).isEqualTo(Status.Code.UNAVAILABLE);
                assertThat(status.getDescription())
                    .isEqualTo("IDENTITY_DATABASE_LOCK_UNAVAILABLE")
                    .doesNotContain("sensitive");
              });
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @ParameterizedTest
  @MethodSource("failures")
  void mapsDatabaseCapacityAndDeadlineFailuresWithoutLeakingCause(
      TransactionFailure failure, Status.Code expectedCode, String expectedDescription) {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    TransactionFailureServerInterceptor interceptor =
        new TransactionFailureServerInterceptor(meters);
    TestServerCall call = new TestServerCall();
    ServerCallHandler<Object, Object> handler =
        (ignoredCall, ignoredHeaders) ->
            new ServerCall.Listener<>() {
              @Override
              public void onHalfClose() {
                throw new TransactionUnavailableException(
                    failure, new IllegalStateException("sensitive database detail"));
              }
            };

    interceptor.interceptCall(call, new Metadata(), handler).onHalfClose();

    assertThat(call.closedStatus.getCode()).isEqualTo(expectedCode);
    assertThat(call.closedStatus.getDescription()).isEqualTo(expectedDescription);
    assertThat(call.closedStatus.getDescription()).doesNotContain("sensitive");
    assertThat(
            meters
                .get("identity.database.transaction.failures")
                .tag("failure", failure.name())
                .counter()
                .count())
        .isEqualTo(1.0d);
  }

  private static Stream<Arguments> failures() {
    return Stream.of(
        Arguments.of(
            TransactionFailure.TRANSACTION_DEADLINE,
            Status.Code.DEADLINE_EXCEEDED,
            "IDENTITY_DATABASE_DEADLINE"),
        Arguments.of(
            TransactionFailure.STATEMENT_TIMEOUT,
            Status.Code.DEADLINE_EXCEEDED,
            "IDENTITY_DATABASE_DEADLINE"),
        Arguments.of(
            TransactionFailure.LOCK_TIMEOUT,
            Status.Code.UNAVAILABLE,
            "IDENTITY_DATABASE_LOCK_UNAVAILABLE"),
        Arguments.of(
            TransactionFailure.POOL_UNAVAILABLE,
            Status.Code.RESOURCE_EXHAUSTED,
            "IDENTITY_DATABASE_POOL_UNAVAILABLE"));
  }

  private static final class TestServerCall extends ServerCall<Object, Object> {
    private Status closedStatus;

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(Object message) {}

    @Override
    public void close(Status status, Metadata trailers) {
      closedStatus = status;
    }

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      return null;
    }
  }
}
