package com.sajtech.identity.interfaces.observability.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IdentityAdmissionInterceptorTest {
  @Test
  void rejectsBeyondGlobalBoundAndReleasesPermitAtCompletion() {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    IdentityAdmissionInterceptor interceptor = new IdentityAdmissionInterceptor(1, meters);
    AtomicInteger started = new AtomicInteger();
    ServerCallHandler<Object, Object> handler =
        (call, headers) -> {
          started.incrementAndGet();
          return new ServerCall.Listener<>() {};
        };

    TestServerCall firstCall = new TestServerCall();
    ServerCall.Listener<Object> first =
        interceptor.interceptCall(firstCall, new Metadata(), handler);
    TestServerCall rejectedCall = new TestServerCall();
    interceptor.interceptCall(rejectedCall, new Metadata(), handler);

    assertThat(started).hasValue(1);
    assertThat(rejectedCall.closedStatus.getCode()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    assertThat(rejectedCall.closedStatus.getDescription()).isEqualTo("IDENTITY_UNAVAILABLE");
    assertThat(meters.get("identity.grpc.admission.rejected").counter().count()).isEqualTo(1.0d);
    assertThat(meters.get("identity.grpc.admission.limit").gauge().value()).isEqualTo(1.0d);

    first.onComplete();
    TestServerCall thirdCall = new TestServerCall();
    ServerCall.Listener<Object> third =
        interceptor.interceptCall(thirdCall, new Metadata(), handler);

    assertThat(started).hasValue(2);
    assertThat(thirdCall.closedStatus).isNull();
    third.onCancel();
    assertThat(meters.get("identity.grpc.admission.in_flight").gauge().value()).isZero();
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
