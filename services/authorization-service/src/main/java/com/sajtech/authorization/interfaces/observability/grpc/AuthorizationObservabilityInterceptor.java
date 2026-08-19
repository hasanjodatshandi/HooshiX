package com.sajtech.authorization.interfaces.observability.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AuthorizationObservabilityInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> TRACEPARENT =
      Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE =
      Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);
  private static final TextMapGetter<Metadata> GETTER = new TraceContextGetter();
  private final Tracer tracer;
  private final MeterRegistry meters;
  private final AtomicInteger inFlight = new AtomicInteger();

  public AuthorizationObservabilityInterceptor(OpenTelemetry telemetry, MeterRegistry meters) {
    this.tracer = Objects.requireNonNull(telemetry).getTracer("com.sajtech.authorization.grpc");
    this.meters = Objects.requireNonNull(meters);
    Gauge.builder("authorization.grpc.server.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String operation = call.getMethodDescriptor().getBareMethodName();
    Context parent =
        W3CTraceContextPropagator.getInstance().extract(Context.root(), headers, GETTER);
    Span span =
        tracer
            .spanBuilder("authorization.grpc")
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    span.setAttribute("rpc.system", "grpc");
    span.setAttribute("rpc.service", "hooshix.authorization.v1.AuthorizationService");
    span.setAttribute("rpc.method", operation);
    Context context = parent.with(span);
    AtomicBoolean ended = new AtomicBoolean();
    long started = System.nanoTime();
    inFlight.incrementAndGet();
    ServerCall<ReqT, RespT> observed =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            span.setAttribute("rpc.grpc.status_code", status.getCode().value());
            if (!status.isOk()) span.setStatus(StatusCode.ERROR);
            try {
              super.close(status, trailers);
            } finally {
              finish(span, ended, operation, status.getCode().name(), started);
            }
          }
        };
    final ServerCall.Listener<ReqT> delegate;
    try (Scope ignored = context.makeCurrent()) {
      delegate = next.startCall(observed, headers);
    } catch (RuntimeException e) {
      span.setStatus(StatusCode.ERROR);
      finish(span, ended, operation, "INTERNAL", started);
      throw e;
    }
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onMessage(ReqT message) {
        scoped(context, () -> super.onMessage(message));
      }

      @Override
      public void onHalfClose() {
        scoped(context, super::onHalfClose);
      }

      @Override
      public void onCancel() {
        try {
          scoped(context, super::onCancel);
        } finally {
          finish(span, ended, operation, "CANCELLED", started);
        }
      }

      @Override
      public void onComplete() {
        try {
          scoped(context, super::onComplete);
        } finally {
          finish(span, ended, operation, "OK", started);
        }
      }

      @Override
      public void onReady() {
        scoped(context, super::onReady);
      }
    };
  }

  private void finish(
      Span span, AtomicBoolean ended, String operation, String outcome, long started) {
    if (!ended.compareAndSet(false, true)) return;
    inFlight.decrementAndGet();
    try {
      Timer.builder("authorization.grpc.server.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    } catch (RuntimeException ignored) {
    }
    span.end();
  }

  private static void scoped(Context context, Runnable work) {
    try (Scope ignored = context.makeCurrent()) {
      work.run();
    }
  }

  private static final class TraceContextGetter implements TextMapGetter<Metadata> {
    @Override
    public Iterable<String> keys(Metadata carrier) {
      return List.of("traceparent", "tracestate");
    }

    @Override
    public String get(Metadata carrier, String key) {
      if (carrier == null) return null;
      return switch (key) {
        case "traceparent" -> carrier.get(TRACEPARENT);
        case "tracestate" -> carrier.get(TRACESTATE);
        default -> null;
      };
    }
  }
}
