package com.sajtech.conversation.interfaces.grpc;

import io.grpc.*;
import java.util.Iterator;

public final class BearerTokenServerInterceptor implements ServerInterceptor {
  static final Context.Key<String> ACCESS_TOKEN = Context.key("conversation-access-token");
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    Iterable<String> values = headers.getAll(AUTHORIZATION);
    Iterator<String> iterator = values == null ? null : values.iterator();
    if (iterator == null || !iterator.hasNext()) return reject(call);
    String value = iterator.next();
    if (iterator.hasNext()
        || value == null
        || !value.startsWith("Bearer ")
        || value.length() <= 7
        || value.length() > 4103) {
      return reject(call);
    }
    return Contexts.interceptCall(
        Context.current().withValue(ACCESS_TOKEN, value.substring(7)), call, headers, next);
  }

  private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call) {
    call.close(Status.UNAUTHENTICATED.withDescription("INVALID_ACCESS_TOKEN"), new Metadata());
    return new ServerCall.Listener<>() {};
  }
}
