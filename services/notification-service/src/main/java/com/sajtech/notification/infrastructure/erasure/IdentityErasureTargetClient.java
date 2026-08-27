package com.sajtech.notification.infrastructure.erasure;

import com.sajtech.identity.contract.v1.BeginParticipantErasureRequest;
import com.sajtech.identity.contract.v1.ErasureParticipant;
import com.sajtech.identity.contract.v1.IdentityErasureServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IdentityErasureTargetClient {
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-erasure-caller", Metadata.ASCII_STRING_MARSHALLER);
  private static final Duration DEADLINE = Duration.ofSeconds(2);
  private final IdentityErasureServiceGrpc.IdentityErasureServiceBlockingStub stub;

  public IdentityErasureTargetClient(ManagedChannel channel) {
    Metadata metadata = new Metadata();
    metadata.put(CALLER, "notification-service");
    stub =
        IdentityErasureServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(metadata)));
  }

  public TargetPage resolve(
      UUID eventId, UUID erasureRequestId, String policyVersion, String pageToken) {
    var response =
        stub.withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
            .beginParticipantErasure(
                BeginParticipantErasureRequest.newBuilder()
                    .setEventId(eventId.toString())
                    .setErasureRequestId(erasureRequestId.toString())
                    .setParticipant(ErasureParticipant.ERASURE_PARTICIPANT_NOTIFICATION_SERVICE)
                    .setParticipantPolicyVersion(policyVersion)
                    .setPageToken(pageToken)
                    .build());
    List<UUID> ids = response.getNotificationIdsList().stream().map(UUID::fromString).toList();
    if (!response.getCompletePage() && response.getNextPageToken().isEmpty()) {
      throw new IllegalStateException("Identity returned an invalid notification target page");
    }
    return new TargetPage(ids, response.getNextPageToken(), response.getCompletePage());
  }

  public record TargetPage(List<UUID> notificationIds, String nextPageToken, boolean complete) {
    public TargetPage {
      notificationIds = List.copyOf(notificationIds);
    }
  }
}
