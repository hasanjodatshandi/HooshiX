package com.sajtech.hooshix.contract.validation;

import build.buf.protovalidate.ValidationResult;
import build.buf.protovalidate.Validator;
import build.buf.protovalidate.ValidatorFactory;
import build.buf.protovalidate.exceptions.ValidationException;
import com.google.protobuf.Message;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Objects;
import java.util.function.Consumer;

/** Enforces the validation annotations carried by HooshiX request contracts. */
public final class ContractValidationServerInterceptor implements ServerInterceptor {
  private static final String INVALID_DESCRIPTION = "INVALID_ARGUMENT";
  private static final String INTERNAL_DESCRIPTION = "CONTRACT_VALIDATION_UNAVAILABLE";

  private final Validator validator;
  private final Consumer<String> rejectionReporter;

  public ContractValidationServerInterceptor(Consumer<String> rejectionReporter) {
    this(ValidatorFactory.newBuilder().build(), rejectionReporter);
  }

  ContractValidationServerInterceptor(
      Validator validator, Consumer<String> rejectionReporter) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.rejectionReporter = Objects.requireNonNull(rejectionReporter, "rejectionReporter");
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    Objects.requireNonNull(call, "call");
    ServerCall.Listener<ReqT> listener = next.startCall(call, headers);
    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
      private boolean rejected;

      @Override
      public void onMessage(ReqT request) {
        if (rejected) {
          return;
        }
        if (!(request instanceof Message message)) {
          closeInternal();
          return;
        }
        try {
          ValidationResult result = validator.validate(message);
          if (result.isSuccess()) {
            super.onMessage(request);
            return;
          }
          rejected = true;
          reportRejection(call.getMethodDescriptor().getFullMethodName());
          call.close(Status.INVALID_ARGUMENT.withDescription(INVALID_DESCRIPTION), new Metadata());
        } catch (ValidationException | RuntimeException exception) {
          closeInternal();
        }
      }

      @Override
      public void onHalfClose() {
        if (!rejected) {
          super.onHalfClose();
        }
      }

      private void closeInternal() {
        rejected = true;
        call.close(Status.INTERNAL.withDescription(INTERNAL_DESCRIPTION), new Metadata());
      }

      private void reportRejection(String methodName) {
        try {
          rejectionReporter.accept(methodName);
        } catch (RuntimeException ignored) {
          // Best-effort telemetry must not change the validation result.
        }
      }
    };
  }
}
