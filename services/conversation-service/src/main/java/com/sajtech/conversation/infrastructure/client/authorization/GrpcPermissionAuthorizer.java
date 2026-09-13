package com.sajtech.conversation.infrastructure.client.authorization;

import com.sajtech.authorization.contract.v1.AuthorizationServiceGrpc;
import com.sajtech.authorization.contract.v1.CheckPermissionRequest;
import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.port.out.PermissionAuthorizer;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class GrpcPermissionAuthorizer implements PermissionAuthorizer {
  static final long DEADLINE_MILLIS = 300;
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-authorization-caller", Metadata.ASCII_STRING_MARSHALLER);
  private final Channel channel;
  private final Semaphore admission;

  public GrpcPermissionAuthorizer(Channel channel, int maximumConcurrentChecks) {
    Objects.requireNonNull(channel);
    if (maximumConcurrentChecks < 1) {
      throw new IllegalArgumentException("Authorization concurrency must be positive");
    }
    Metadata metadata = new Metadata();
    metadata.put(CALLER, "conversation-service");
    this.channel =
        ClientInterceptors.intercept(channel, MetadataUtils.newAttachHeadersInterceptor(metadata));
    this.admission = new Semaphore(maximumConcurrentChecks);
  }

  @Override
  public void check(ConversationActor actor, String permissionKey) {
    Objects.requireNonNull(actor);
    if (permissionKey == null
        || !permissionKey.matches("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)+")
        || permissionKey.length() > 128) {
      throw new IllegalArgumentException("Permission key is invalid");
    }
    if (!admission.tryAcquire()) throw unavailable(null);
    try {
      AuthorizationServiceGrpc.newBlockingStub(channel)
          .withDeadlineAfter(DEADLINE_MILLIS, TimeUnit.MILLISECONDS)
          .checkPermission(
              CheckPermissionRequest.newBuilder()
                  .setTenantId(actor.tenantId().toString())
                  .setMembershipId(actor.membershipId().toString())
                  .setPermissionKey(permissionKey)
                  .build());
    } catch (StatusRuntimeException exception) {
      if (exception.getStatus().getCode() == Status.Code.PERMISSION_DENIED) {
        throw new ConversationException(
            ConversationError.AUTHORIZATION_DENIED, "Authorization denied", exception);
      }
      throw unavailable(exception);
    } finally {
      admission.release();
    }
  }

  private static ConversationException unavailable(Throwable cause) {
    return cause == null
        ? new ConversationException(
            ConversationError.AUTHORIZATION_UNAVAILABLE, "Authorization unavailable")
        : new ConversationException(
            ConversationError.AUTHORIZATION_UNAVAILABLE, "Authorization unavailable", cause);
  }
}
