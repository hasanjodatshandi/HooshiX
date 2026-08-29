package com.sajtech.identity.interfaces.observability.grpc;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;

public final class TransactionFailureServerInterceptor implements ServerInterceptor {
  private final MeterRegistry meters;

  public TransactionFailureServerInterceptor(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    ServerCall.Listener<ReqT> delegate = next.startCall(call, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onHalfClose() {
        try {
          super.onHalfClose();
        } catch (TransactionUnavailableException exception) {
          TransactionFailure failure = exception.failure();
          record(failure);
          call.close(status(failure), new Metadata());
        }
      }
    };
  }

  private void record(TransactionFailure failure) {
    try {
      meters
          .counter("identity.database.transaction.failures", "failure", failure.name())
          .increment();
    } catch (RuntimeException ignored) {
      // Ordinary telemetry failure must not change the safe database failure response.
    }
  }

  private static Status status(TransactionFailure failure) {
    return switch (failure) {
      case TRANSACTION_DEADLINE, STATEMENT_TIMEOUT ->
          Status.DEADLINE_EXCEEDED.withDescription("IDENTITY_DATABASE_DEADLINE");
      case LOCK_TIMEOUT -> Status.UNAVAILABLE.withDescription("IDENTITY_DATABASE_LOCK_UNAVAILABLE");
      case POOL_UNAVAILABLE ->
          Status.RESOURCE_EXHAUSTED.withDescription("IDENTITY_DATABASE_POOL_UNAVAILABLE");
    };
  }
}
