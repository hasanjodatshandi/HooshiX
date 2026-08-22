package com.sajtech.identity.interfaces.observability.grpc;

import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class IdentityAdmissionInterceptor implements ServerInterceptor {
  private static final Metadata.Key<String> ERROR_CODE =
      Metadata.Key.of("x-hooshix-error-code", Metadata.ASCII_STRING_MARSHALLER);
  private static final String UNAVAILABLE_CODE = "IDENTITY_UNAVAILABLE";

  private final Semaphore permits;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Counter rejected;

  public IdentityAdmissionInterceptor(int maximumConcurrentCalls, MeterRegistry meters) {
    if (maximumConcurrentCalls <= 0) {
      throw new IllegalArgumentException("Maximum global Identity concurrency must be positive");
    }
    permits = new Semaphore(maximumConcurrentCalls, true);
    Gauge.builder("identity.grpc.admission.in_flight", inFlight, AtomicInteger::get)
        .register(meters);
    Gauge.builder("identity.grpc.admission.limit", () -> maximumConcurrentCalls).register(meters);
    rejected = Counter.builder("identity.grpc.admission.rejected").register(meters);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!permits.tryAcquire()) {
      rejected.increment();
      Metadata trailers = new Metadata();
      trailers.put(ERROR_CODE, UNAVAILABLE_CODE);
      call.close(Status.RESOURCE_EXHAUSTED.withDescription(UNAVAILABLE_CODE), trailers);
      return new ServerCall.Listener<>() {};
    }

    inFlight.incrementAndGet();
    AtomicBoolean released = new AtomicBoolean();
    ServerCall<ReqT, RespT> boundedCall =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            try {
              super.close(status, trailers);
            } finally {
              releaseOnce(released);
            }
          }
        };

    final ServerCall.Listener<ReqT> delegate;
    try {
      delegate = next.startCall(boundedCall, headers);
    } catch (RuntimeException exception) {
      releaseOnce(released);
      throw exception;
    }

    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onCancel() {
        try {
          super.onCancel();
        } finally {
          releaseOnce(released);
        }
      }

      @Override
      public void onComplete() {
        try {
          super.onComplete();
        } finally {
          releaseOnce(released);
        }
      }
    };
  }

  private void releaseOnce(AtomicBoolean released) {
    if (released.compareAndSet(false, true)) {
      inFlight.decrementAndGet();
      permits.release();
    }
  }
}
