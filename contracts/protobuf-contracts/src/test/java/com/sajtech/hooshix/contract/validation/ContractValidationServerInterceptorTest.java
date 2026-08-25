package com.sajtech.hooshix.contract.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class ContractValidationServerInterceptorTest {
  private final AtomicInteger rejections = new AtomicInteger();
  private ManagedChannel channel;
  private Server server;

  @BeforeEach
  void startServer() throws IOException {
    String serverName = "contract-validation-" + UUID.randomUUID();
    var service =
        new CompromisedPasswordServiceGrpc.CompromisedPasswordServiceImplBase() {
          @Override
          public void lookupPrefix(
              LookupPrefixRequest request, StreamObserver<LookupPrefixResponse> observer) {
            observer.onNext(LookupPrefixResponse.getDefaultInstance());
            observer.onCompleted();
          }
        };
    var interceptor = new ContractValidationServerInterceptor(ignored -> rejections.incrementAndGet());
    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(ServerInterceptors.intercept(service, interceptor))
            .build()
            .start();
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void validRequestReachesService() {
    var stub = CompromisedPasswordServiceGrpc.newBlockingStub(channel);

    assertDoesNotThrow(
        () -> stub.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("ABCDE").build()));
    assertEquals(0, rejections.get());
  }

  @Test
  void invalidRequestIsRejectedBeforeService() {
    var stub = CompromisedPasswordServiceGrpc.newBlockingStub(channel);

    StatusRuntimeException failure =
        assertThrows(
            StatusRuntimeException.class,
            () -> stub.lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("abcde").build()));

    assertEquals(Status.Code.INVALID_ARGUMENT, failure.getStatus().getCode());
    assertEquals("INVALID_ARGUMENT", failure.getStatus().getDescription());
    assertEquals(1, rejections.get());
  }
}
