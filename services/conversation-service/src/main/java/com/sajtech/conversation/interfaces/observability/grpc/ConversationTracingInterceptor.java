package com.sajtech.conversation.interfaces.observability.grpc;

import io.grpc.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConversationTracingInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> TRACEPARENT =
      Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
  private static final Metadata.Key<String> TRACESTATE =
      Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);
  private static final TextMapGetter<Metadata> TRACE_CONTEXT = new TraceContextGetter();
  private static final Set<String> METHODS =
      Set.of(
          "CreateConversation",
          "ListConversations",
          "GetConversation",
          "ArchiveConversation",
          "DeleteConversation",
          "ListMessages",
          "CreateModelRun",
          "GetModelRun",
          "CancelModelRun",
          "SubmitRunFeedback");
  private final Tracer tracer;
  private final MeterRegistry meters;

  public ConversationTracingInterceptor(OpenTelemetry telemetry, MeterRegistry meters) {
    tracer = Objects.requireNonNull(telemetry).getTracer("com.sajtech.conversation.grpc");
    this.meters = Objects.requireNonNull(meters);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String bare = call.getMethodDescriptor().getBareMethodName();
    String operation = METHODS.contains(bare) ? bare : "Unknown";
    long started = System.nanoTime();
    var parent =
        W3CTraceContextPropagator.getInstance()
            .extract(io.opentelemetry.context.Context.root(), headers, TRACE_CONTEXT);
    Span span =
        tracer
            .spanBuilder("conversation." + operation)
            .setParent(parent)
            .setSpanKind(SpanKind.SERVER)
            .startSpan();
    var context = parent.with(span);
    AtomicBoolean ended = new AtomicBoolean();
    ServerCall<ReqT, RespT> traced =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            span.setAttribute("rpc.grpc.status_code", status.getCode().value());
            if (!status.isOk()) span.setStatus(StatusCode.ERROR);
            try {
              super.close(status, trailers);
            } finally {
              Timer.builder("conversation.grpc.duration")
                  .tag("operation", operation)
                  .tag("outcome", status.getCode().name())
                  .register(meters)
                  .record(Duration.ofNanos(System.nanoTime() - started));
              end(span, ended);
            }
          }
        };
    final ServerCall.Listener<ReqT> delegate;
    try (Scope ignored = context.makeCurrent()) {
      delegate = next.startCall(traced, headers);
    } catch (RuntimeException exception) {
      span.setStatus(StatusCode.ERROR);
      end(span, ended);
      throw exception;
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
          end(span, ended);
        }
      }

      @Override
      public void onComplete() {
        try {
          scoped(context, super::onComplete);
        } finally {
          end(span, ended);
        }
      }
    };
  }

  private static void scoped(io.opentelemetry.context.Context context, Runnable action) {
    try (Scope ignored = context.makeCurrent()) {
      action.run();
    }
  }

  private static void end(Span span, AtomicBoolean ended) {
    if (ended.compareAndSet(false, true)) span.end();
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
