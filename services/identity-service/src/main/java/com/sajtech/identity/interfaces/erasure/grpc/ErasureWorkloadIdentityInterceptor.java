package com.sajtech.identity.interfaces.erasure.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

public final class ErasureWorkloadIdentityInterceptor implements ServerInterceptor {
  public static final String HEADER_NAME = "x-hooshix-erasure-caller";
  public static final Context.Key<String> WORKLOAD = Context.key("identity-erasure-workload");
  private static final Metadata.Key<String> HEADER =
      Metadata.Key.of(HEADER_NAME, Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String workload = headers.get(HEADER);
    return Contexts.interceptCall(
        Context.current().withValue(WORKLOAD, workload), call, headers, next);
  }
}
