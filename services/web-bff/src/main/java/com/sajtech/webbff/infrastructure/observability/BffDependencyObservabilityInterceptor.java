package com.sajtech.webbff.infrastructure.observability;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.micrometer.core.instrument.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BffDependencyObservabilityInterceptor implements ClientInterceptor {
  private static final TextMapSetter<Metadata> SETTER =
      (carrier, key, value) -> {
        if (carrier != null && value != null)
          carrier.put(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER), value);
      };
  private final String dependency;
  private final Tracer tracer;
  private final MeterRegistry meters;

  public BffDependencyObservabilityInterceptor(
      String dependency, OpenTelemetry telemetry, MeterRegistry meters) {
    this.dependency = Objects.requireNonNull(dependency);
    this.tracer = Objects.requireNonNull(telemetry).getTracer("com.sajtech.webbff.grpc");
    this.meters = Objects.requireNonNull(meters);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    String operation = method.getBareMethodName();
    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
      @Override
      public void start(Listener<RespT> listener, Metadata headers) {
        Context parent = Context.current();
        Span span =
            tracer
                .spanBuilder("web-bff.grpc")
                .setParent(parent)
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        span.setAttribute("rpc.system", "grpc");
        span.setAttribute("server.service", dependency);
        span.setAttribute("rpc.method", operation);
        Context context = parent.with(span);
        W3CTraceContextPropagator.getInstance().inject(context, headers, SETTER);
        long started = System.nanoTime();
        AtomicBoolean ended = new AtomicBoolean();
        Listener<RespT> observed =
            new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
              @Override
              public void onClose(Status status, Metadata trailers) {
                try {
                  super.onClose(status, trailers);
                } finally {
                  if (ended.compareAndSet(false, true)) {
                    span.setAttribute("rpc.grpc.status_code", status.getCode().value());
                    if (!status.isOk()) span.setStatus(StatusCode.ERROR);
                    Timer.builder("web_bff.dependency.duration")
                        .tag("dependency", dependency)
                        .tag("operation", operation)
                        .tag("outcome", status.getCode().name())
                        .register(meters)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                    span.end();
                  }
                }
              }
            };
        try (Scope ignored = context.makeCurrent()) {
          super.start(observed, headers);
        } catch (RuntimeException e) {
          if (ended.compareAndSet(false, true)) {
            span.setStatus(StatusCode.ERROR);
            Timer.builder("web_bff.dependency.duration")
                .tag("dependency", dependency)
                .tag("operation", operation)
                .tag("outcome", "START_FAILURE")
                .register(meters)
                .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            span.end();
          }
          throw e;
        }
      }
    };
  }
}
