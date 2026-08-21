package com.sajtech.authorization.interfaces.grpc;

import com.sajtech.authorization.application.AuthorizationException;
import com.sajtech.authorization.application.model.ActorContext;
import com.sajtech.authorization.application.port.out.AccessTokenVerifier;
import io.grpc.*;
import java.util.Set;

public final class JwtActorServerInterceptor implements ServerInterceptor {
  public static final Context.Key<ActorContext> ACTOR = Context.key("authorization-actor");
  private static final Metadata.Key<String> AUTHORIZATION =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  private static final Set<String> MANAGEMENT =
      Set.of(
          "ListPermissions",
          "ListRoles",
          "GetRole",
          "GetMembershipAuthorization",
          "CreateRole",
          "UpdateRole",
          "ArchiveRole",
          "ReplaceRolePermissions",
          "AssignRoleToMembership",
          "RemoveRoleFromMembership",
          "SetMembershipPermissionOverride",
          "RemoveMembershipPermissionOverride");
  private final AccessTokenVerifier verifier;

  public JwtActorServerInterceptor(AccessTokenVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    String bare = call.getMethodDescriptor().getBareMethodName();
    if (!MANAGEMENT.contains(bare)) return next.startCall(call, headers);
    String value = headers.get(AUTHORIZATION);
    if (value == null || !value.startsWith("Bearer ") || value.length() <= 7) {
      call.close(Status.UNAUTHENTICATED.withDescription("INVALID_ACCESS_TOKEN"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    try {
      ActorContext actor = verifier.verify(value.substring(7));
      return Contexts.interceptCall(Context.current().withValue(ACTOR, actor), call, headers, next);
    } catch (AuthorizationException e) {
      Status status =
          e.error().name().equals("AUTHORIZATION_UNAVAILABLE")
              ? Status.UNAVAILABLE
              : Status.UNAUTHENTICATED;
      call.close(status.withDescription(e.error().name()), new Metadata());
      return new ServerCall.Listener<>() {};
    }
  }
}
