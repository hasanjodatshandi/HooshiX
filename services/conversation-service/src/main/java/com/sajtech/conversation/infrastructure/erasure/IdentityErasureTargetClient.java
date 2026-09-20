package com.sajtech.conversation.infrastructure.erasure;

import com.sajtech.identity.contract.v1.BeginParticipantErasureRequest;
import com.sajtech.identity.contract.v1.ErasureParticipant;
import com.sajtech.identity.contract.v1.IdentityErasureServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IdentityErasureTargetClient {
  private static final Metadata.Key<String> CALLER =
      Metadata.Key.of("x-hooshix-erasure-caller", Metadata.ASCII_STRING_MARSHALLER);
  private static final Duration DEADLINE = Duration.ofSeconds(2);
  private final IdentityErasureServiceGrpc.IdentityErasureServiceBlockingStub stub;

  public IdentityErasureTargetClient(ManagedChannel channel) {
    Metadata metadata = new Metadata();
    metadata.put(CALLER, "conversation-service");
    this.stub =
        IdentityErasureServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(metadata)));
  }

  public UUID resolve(UUID eventId, UUID erasureRequestId, String policyVersion) {
    var response =
        stub.withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
            .beginParticipantErasure(
                BeginParticipantErasureRequest.newBuilder()
                    .setEventId(eventId.toString())
                    .setErasureRequestId(erasureRequestId.toString())
                    .setParticipant(ErasureParticipant.ERASURE_PARTICIPANT_CONVERSATION_SERVICE)
                    .setParticipantPolicyVersion(policyVersion)
                    .build());
    if (!response.getCompletePage()
        || !response.getNextPageToken().isEmpty()
        || response.getUserId().isEmpty()
        || response.getNotificationIdsCount() != 0) {
      throw new IllegalStateException("Identity returned an invalid Conversation erasure target");
    }
    return UUID.fromString(response.getUserId());
  }
}
