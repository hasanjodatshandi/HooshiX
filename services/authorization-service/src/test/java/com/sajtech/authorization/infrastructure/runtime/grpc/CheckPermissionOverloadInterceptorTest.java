package com.sajtech.authorization.infrastructure.runtime.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CheckPermissionOverloadInterceptorTest {
  @Test
  void missingCallerContextMapsToFailClosedUnavailable() {
    var interceptor = new CheckPermissionOverloadInterceptor(controller(1));
    ServerCall<Object, Object> call = call();
    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

    interceptor.interceptCall(call, new Metadata(), next);

    assertClosed(call, Status.Code.UNAVAILABLE, "AUTHORIZATION_UNAVAILABLE");
    verifyNoInteractions(next);
  }

  @Test
  void saturatedCallerMapsToStableResourceExhausted() {
    var admission = controller(1);
    var interceptor = new CheckPermissionOverloadInterceptor(admission);
    Metadata headers = new Metadata();
    headers.put(
        Metadata.Key.of(
            CheckPermissionOverloadInterceptor.CALLER_HEADER_NAME,
            Metadata.ASCII_STRING_MARSHALLER),
        "caller-a");
    ServerCall<Object, Object> call = call();
    ServerCallHandler<Object, Object> next = mock(ServerCallHandler.class);

    try (var lease = admission.acquire("caller-a")) {
      interceptor.interceptCall(call, headers, next);
    }

    assertClosed(call, Status.Code.RESOURCE_EXHAUSTED, "AUTHORIZATION_OVERLOADED");
    verifyNoInteractions(next);
  }

  private static CheckPermissionAdmissionController controller(int concurrency) {
    return new CheckPermissionAdmissionController(
        concurrency,
        concurrency,
        1,
        1,
        4,
        Duration.ofMillis(1),
        new AuthorizationCheckPermissionMetrics(new SimpleMeterRegistry()));
  }

  @SuppressWarnings("unchecked")
  private static ServerCall<Object, Object> call() {
    ServerCall<Object, Object> call = mock(ServerCall.class);
    MethodDescriptor<Object, Object> descriptor = mock(MethodDescriptor.class);
    when(descriptor.getBareMethodName()).thenReturn("CheckPermission");
    when(call.getMethodDescriptor()).thenReturn(descriptor);
    return call;
  }

  private static void assertClosed(
      ServerCall<Object, Object> call, Status.Code code, String description) {
    ArgumentCaptor<Status> status = ArgumentCaptor.forClass(Status.class);
    verify(call).close(status.capture(), any(Metadata.class));
    assertThat(status.getValue().getCode()).isEqualTo(code);
    assertThat(status.getValue().getDescription()).isEqualTo(description);
  }
}
