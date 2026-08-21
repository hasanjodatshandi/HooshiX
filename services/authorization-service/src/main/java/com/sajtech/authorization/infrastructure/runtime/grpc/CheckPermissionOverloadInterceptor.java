package com.sajtech.authorization.infrastructure.runtime.grpc;

import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics.ShedReason;
import com.sajtech.authorization.infrastructure.runtime.grpc.CheckPermissionAdmissionController.AdmissionRejected;
import com.sajtech.authorization.infrastructure.runtime.grpc.CheckPermissionAdmissionController.Lease;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;
import java.util.function.Function;

public final class CheckPermissionOverloadInterceptor implements ServerInterceptor {
  public static final String CALLER_HEADER_NAME = "x-hooshix-authorization-caller";
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of(CALLER_HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);
  private final Function<String, Lease> acquireLease;

  public CheckPermissionOverloadInterceptor(CheckPermissionAdmissionController admission) {
    this.acquireLease = Objects.requireNonNull(admission)::acquire;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    if (!"CheckPermission".equals(call.getMethodDescriptor().getBareMethodName()))
      return next.startCall(call, headers);
    final Lease lease;
    try {
      lease = acquireLease.apply(headers.get(CALLER));
    } catch (AdmissionRejected rejected) {
      Status status =
          rejected.reason() == ShedReason.CALLER_CONTEXT
              ? Status.UNAVAILABLE.withDescription("AUTHORIZATION_UNAVAILABLE")
              : Status.RESOURCE_EXHAUSTED.withDescription("AUTHORIZATION_OVERLOADED");
      call.close(status, new Metadata());
      return new ServerCall.Listener<>() {};
    }
    ServerCall<ReqT, RespT> admittedCall =
        new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            try {
              super.close(status, trailers);
            } finally {
              lease.close();
            }
          }
        };
    final ServerCall.Listener<ReqT> delegate;
    try {
      delegate = next.startCall(admittedCall, headers);
    } catch (RuntimeException failure) {
      lease.close();
      throw failure;
    }
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(delegate) {
      @Override
      public void onCancel() {
        try {
          super.onCancel();
        } finally {
          lease.close();
        }
      }

      @Override
      public void onComplete() {
        try {
          super.onComplete();
        } finally {
          lease.close();
        }
      }
    };
  }
}
