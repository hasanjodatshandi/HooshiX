package com.sajtech.webbff.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BffDependencyAdmissionInterceptorTest {
  private static final MethodDescriptor<String, String> METHOD =
      MethodDescriptor.<String, String>newBuilder()
          .setType(MethodDescriptor.MethodType.UNARY)
          .setFullMethodName("test.Service/Call")
          .setRequestMarshaller(new Strings())
          .setResponseMarshaller(new Strings())
          .build();

  @Test
  void rejectsWithoutQueueAndReleasesPermitOnClose() {
    var meters = new SimpleMeterRegistry();
    var channel = new HoldingChannel();
    var interceptor = new BffDependencyAdmissionInterceptor("identity", 1, meters);
    interceptor
        .interceptCall(METHOD, CallOptions.DEFAULT, channel)
        .start(new ClientCall.Listener<>() {}, new Metadata());
    AtomicReference<Status> rejected = new AtomicReference<>();
    interceptor
        .interceptCall(METHOD, CallOptions.DEFAULT, channel)
        .start(
            new ClientCall.Listener<>() {
              @Override
              public void onClose(Status status, Metadata trailers) {
                rejected.set(status);
              }
            },
            new Metadata());
    assertThat(rejected.get().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(meters.get("web_bff.dependency.admission.rejected").counter().count()).isEqualTo(1);
    channel.close(Status.OK);
    AtomicReference<Status> third = new AtomicReference<>();
    interceptor
        .interceptCall(METHOD, CallOptions.DEFAULT, channel)
        .start(
            new ClientCall.Listener<>() {
              @Override
              public void onClose(Status status, Metadata trailers) {
                third.set(status);
              }
            },
            new Metadata());
    assertThat(third.get()).isNull();
  }

  private static final class HoldingChannel extends Channel {
    private ClientCall.Listener<String> listener;

    public String authority() {
      return "test";
    }

    @SuppressWarnings("unchecked")
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
        MethodDescriptor<ReqT, RespT> m, CallOptions o) {
      return new ClientCall<>() {
        public void start(Listener<RespT> l, Metadata h) {
          listener = (ClientCall.Listener<String>) l;
        }

        public void request(int n) {}

        public void cancel(String m, Throwable c) {}

        public void halfClose() {}

        public void sendMessage(ReqT m) {}
      };
    }

    void close(Status status) {
      listener.onClose(status, new Metadata());
    }
  }

  private static final class Strings implements MethodDescriptor.Marshaller<String> {
    public InputStream stream(String v) {
      return new java.io.ByteArrayInputStream(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String parse(InputStream in) {
      return "";
    }
  }
}
