package com.sajtech.webbff.infrastructure.observability;

import io.grpc.*;
import io.micrometer.core.instrument.*;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class BffDependencyAdmissionInterceptor implements ClientInterceptor {
  private final Semaphore permits;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final Counter rejected;

  public BffDependencyAdmissionInterceptor(
      String dependency, int maximumConcurrentCalls, MeterRegistry meters) {
    Objects.requireNonNull(dependency);
    if (maximumConcurrentCalls < 1)
      throw new IllegalArgumentException("Dependency concurrency limit must be positive");
    permits = new Semaphore(maximumConcurrentCalls);
    rejected =
        Counter.builder("web_bff.dependency.admission.rejected")
            .tag("dependency", dependency)
            .register(Objects.requireNonNull(meters));
    Gauge.builder("web_bff.dependency.admission.in_flight", inFlight, AtomicInteger::get)
        .tag("dependency", dependency)
        .register(meters);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
    ClientCall<ReqT, RespT> delegate = next.newCall(method, options);
    return new ClientCall<>() {
      private final AtomicBoolean released = new AtomicBoolean();
      private volatile boolean admitted;
      private boolean started;

      public void start(Listener<RespT> listener, Metadata headers) {
        if (started) throw new IllegalStateException("ClientCall already started");
        started = true;
        admitted = permits.tryAcquire();
        if (!admitted) {
          rejected.increment();
          listener.onClose(
              Status.UNAVAILABLE.withDescription("BFF dependency admission saturated"),
              new Metadata());
          return;
        }
        inFlight.incrementAndGet();
        try {
          delegate.start(
              new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(listener) {
                @Override
                public void onClose(Status status, Metadata trailers) {
                  try {
                    super.onClose(status, trailers);
                  } finally {
                    release();
                  }
                }
              },
              headers);
        } catch (RuntimeException e) {
          release();
          throw e;
        }
      }

      public void request(int n) {
        if (admitted) delegate.request(n);
      }

      public void cancel(String message, Throwable cause) {
        if (admitted) {
          try {
            delegate.cancel(message, cause);
          } finally {
            release();
          }
        }
      }

      public void halfClose() {
        if (admitted) delegate.halfClose();
      }

      public void sendMessage(ReqT message) {
        if (admitted) delegate.sendMessage(message);
      }

      private void release() {
        if (admitted && released.compareAndSet(false, true)) {
          inFlight.decrementAndGet();
          permits.release();
        }
      }
    };
  }
}
