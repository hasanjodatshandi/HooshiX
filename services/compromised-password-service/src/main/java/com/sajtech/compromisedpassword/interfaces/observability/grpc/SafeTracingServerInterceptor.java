package com.sajtech.compromisedpassword.interfaces.observability.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SafeTracingServerInterceptor implements ServerInterceptor {
    private static final Metadata.Key<String> TRACEPARENT =
            Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> TRACESTATE =
            Metadata.Key.of("tracestate", Metadata.ASCII_STRING_MARSHALLER);
    private static final TextMapGetter<Metadata> TRACE_CONTEXT_GETTER = new TraceContextGetter();

    private final Tracer tracer;

    public SafeTracingServerInterceptor(OpenTelemetry openTelemetry) {
        this.tracer =
                Objects.requireNonNull(openTelemetry, "openTelemetry")
                        .getTracer("com.sajtech.compromisedpassword.grpc");
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        Context parent =
                W3CTraceContextPropagator.getInstance()
                        .extract(Context.root(), headers, TRACE_CONTEXT_GETTER);
        Span span =
                tracer.spanBuilder("compromised-password.lookup")
                        .setParent(parent)
                        .setSpanKind(SpanKind.SERVER)
                        .startSpan();
        Context spanContext = parent.with(span);
        AtomicBoolean ended = new AtomicBoolean();

        ServerCall<ReqT, RespT> tracedCall =
                new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
                    @Override
                    public void close(Status status, Metadata trailers) {
                        span.setAttribute("rpc.grpc.status_code", status.getCode().value());
                        if (!status.isOk()) {
                            span.setStatus(StatusCode.ERROR);
                        }
                        try {
                            super.close(status, trailers);
                        } finally {
                            endOnce(span, ended);
                        }
                    }
                };

        final ServerCall.Listener<ReqT> delegate;
        try (Scope ignored = spanContext.makeCurrent()) {
            delegate = next.startCall(tracedCall, headers);
        } catch (RuntimeException exception) {
            span.setStatus(StatusCode.ERROR);
            endOnce(span, ended);
            throw exception;
        }

        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
            @Override
            public void onMessage(ReqT message) {
                runScoped(spanContext, () -> super.onMessage(message));
            }

            @Override
            public void onHalfClose() {
                runScoped(spanContext, super::onHalfClose);
            }

            @Override
            public void onCancel() {
                try {
                    runScoped(spanContext, super::onCancel);
                } finally {
                    endOnce(span, ended);
                }
            }

            @Override
            public void onComplete() {
                try {
                    runScoped(spanContext, super::onComplete);
                } finally {
                    endOnce(span, ended);
                }
            }

            @Override
            public void onReady() {
                runScoped(spanContext, super::onReady);
            }
        };
    }

    private static void runScoped(Context context, Runnable action) {
        try (Scope ignored = context.makeCurrent()) {
            action.run();
        }
    }

    private static void endOnce(Span span, AtomicBoolean ended) {
        if (ended.compareAndSet(false, true)) {
            span.end();
        }
    }

    private static final class TraceContextGetter implements TextMapGetter<Metadata> {
        @Override
        public Iterable<String> keys(Metadata carrier) {
            return List.of("traceparent", "tracestate");
        }

        @Override
        public String get(Metadata carrier, String key) {
            if (carrier == null) {
                return null;
            }
            return switch (key) {
                case "traceparent" -> carrier.get(TRACEPARENT);
                case "tracestate" -> carrier.get(TRACESTATE);
                default -> null;
            };
        }
    }
}
