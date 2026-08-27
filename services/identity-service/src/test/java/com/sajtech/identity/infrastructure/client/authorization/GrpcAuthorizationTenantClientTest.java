package com.sajtech.identity.infrastructure.client.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.authorization.contract.v1.AuthorizationServiceGrpc;
import com.sajtech.authorization.contract.v1.CheckPermissionRequest;
import com.sajtech.authorization.contract.v1.CheckPermissionResponse;
import com.sajtech.authorization.contract.v1.CheckPlatformPermissionRequest;
import com.sajtech.authorization.contract.v1.CheckPlatformPermissionResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GrpcAuthorizationTenantClientTest {
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-authorization-caller", Metadata.ASCII_STRING_MARSHALLER);

  @Test
  void attachesFixedIdentityCallerProjectionToAuthorizationCalls() throws Exception {
    String serverName = InProcessServerBuilder.generateName();
    AtomicReference<String> caller = new AtomicReference<>();
    var service =
        new AuthorizationServiceGrpc.AuthorizationServiceImplBase() {
          @Override
          public void checkPermission(
              CheckPermissionRequest request, StreamObserver<CheckPermissionResponse> observer) {
            observer.onNext(CheckPermissionResponse.getDefaultInstance());
            observer.onCompleted();
          }

          @Override
          public void checkPlatformPermission(
              CheckPlatformPermissionRequest request,
              StreamObserver<CheckPlatformPermissionResponse> observer) {
            observer.onNext(CheckPlatformPermissionResponse.getDefaultInstance());
            observer.onCompleted();
          }
        };
    Server server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(
                    service,
                    new ServerInterceptor() {
                      @Override
                      public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                          ServerCall<ReqT, RespT> call,
                          Metadata headers,
                          ServerCallHandler<ReqT, RespT> next) {
                        caller.set(headers.get(CALLER));
                        return next.startCall(call, headers);
                      }
                    }))
            .build()
            .start();
    ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    try {
      GrpcAuthorizationTenantClient client = new GrpcAuthorizationTenantClient(channel);
      client.checkPermission(UUID.randomUUID(), UUID.randomUUID(), "membership.role.assign");
      client.checkPlatformPermission(UUID.randomUUID(), "platform.tenant.suspend");
      assertThat(caller.get()).isEqualTo("identity-service");
    } finally {
      channel.shutdownNow();
      channel.awaitTermination(5, TimeUnit.SECONDS);
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
