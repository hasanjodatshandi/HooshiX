package com.sajtech.authorization.infrastructure.runtime.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sajtech.authorization.application.port.out.AccessTokenVerifier;
import com.sajtech.authorization.contract.v1.AuthorizationServiceGrpc;
import com.sajtech.authorization.contract.v1.CheckPermissionRequest;
import com.sajtech.authorization.contract.v1.CheckPermissionResponse;
import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics;
import com.sajtech.authorization.interfaces.grpc.JwtActorServerInterceptor;
import com.sajtech.authorization.interfaces.observability.grpc.AuthorizationObservabilityInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuthorizationGrpcInterceptorCompositionTest {
  @Test
  void telemetryOutermostObservesCheckPermissionOverload() throws Exception {
    var meters = new SimpleMeterRegistry();
    var admission =
        new CheckPermissionAdmissionController(
            1, 1, 1, 1, 4, Duration.ofMillis(1), new AuthorizationCheckPermissionMetrics(meters));
    var telemetry = new AuthorizationObservabilityInterceptor(OpenTelemetry.noop(), meters);
    var overload = new CheckPermissionOverloadInterceptor(admission);
    var jwt = new JwtActorServerInterceptor(mock(AccessTokenVerifier.class));

    try (var harness = startHarness(List.of(jwt, overload, telemetry))) {
      try (var lease = admission.acquire("caller-a")) {
        Metadata headers = new Metadata();
        headers.put(
            Metadata.Key.of(
                CheckPermissionOverloadInterceptor.CALLER_HEADER_NAME,
                Metadata.ASCII_STRING_MARSHALLER),
            "caller-a");
        var stub =
            AuthorizationServiceGrpc.newBlockingStub(
                ClientInterceptors.intercept(
                    harness.channel(), MetadataUtils.newAttachHeadersInterceptor(headers)));

        assertThatThrownBy(
                () ->
                    stub.checkPermission(
                        CheckPermissionRequest.newBuilder()
                            .setTenantId("00000000-0000-4000-8000-000000000001")
                            .setMembershipId("00000000-0000-4000-8000-000000000002")
                            .setPermissionKey("tenant.read")
                            .build()))
            .isInstanceOf(StatusRuntimeException.class)
            .satisfies(
                error ->
                    assertThat(Status.fromThrowable(error).getCode())
                        .isEqualTo(Status.Code.RESOURCE_EXHAUSTED));
      }
    }

    assertThat(
            meters
                .get("authorization.grpc.server.duration")
                .tags("operation", "CheckPermission", "outcome", "RESOURCE_EXHAUSTED")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void telemetryOutermostObservesMissingCallerContext() throws Exception {
    var meters = new SimpleMeterRegistry();
    var admission =
        new CheckPermissionAdmissionController(
            1, 1, 1, 1, 4, Duration.ofMillis(1), new AuthorizationCheckPermissionMetrics(meters));
    var telemetry = new AuthorizationObservabilityInterceptor(OpenTelemetry.noop(), meters);
    var overload = new CheckPermissionOverloadInterceptor(admission);
    var jwt = new JwtActorServerInterceptor(mock(AccessTokenVerifier.class));

    try (var harness = startHarness(List.of(jwt, overload, telemetry))) {
      var stub = AuthorizationServiceGrpc.newBlockingStub(harness.channel());
      assertThatThrownBy(
              () ->
                  stub.checkPermission(
                      CheckPermissionRequest.newBuilder()
                          .setTenantId("00000000-0000-4000-8000-000000000001")
                          .setMembershipId("00000000-0000-4000-8000-000000000002")
                          .setPermissionKey("tenant.read")
                          .build()))
          .isInstanceOf(StatusRuntimeException.class)
          .satisfies(
              error ->
                  assertThat(Status.fromThrowable(error).getCode())
                      .isEqualTo(Status.Code.UNAVAILABLE));
    }

    assertThat(
            meters
                .get("authorization.grpc.server.duration")
                .tags("operation", "CheckPermission", "outcome", "UNAVAILABLE")
                .timer()
                .count())
        .isEqualTo(1);
  }

  private static Harness startHarness(List<io.grpc.ServerInterceptor> interceptors)
      throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(new AcceptingService(), interceptors))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    return new Harness(server, channel);
  }

  private static final class AcceptingService
      extends AuthorizationServiceGrpc.AuthorizationServiceImplBase {
    @Override
    public void checkPermission(
        CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
      observer.onNext(CheckPermissionResponse.getDefaultInstance());
      observer.onCompleted();
    }
  }

  private record Harness(Server server, ManagedChannel channel) implements AutoCloseable {
    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
