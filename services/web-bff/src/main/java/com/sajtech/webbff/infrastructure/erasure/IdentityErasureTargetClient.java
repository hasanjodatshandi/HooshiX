package com.sajtech.webbff.infrastructure.erasure;

import com.sajtech.identity.contract.v1.BeginParticipantErasureRequest;
import com.sajtech.identity.contract.v1.ErasureParticipant;
import com.sajtech.identity.contract.v1.IdentityErasureServiceGrpc;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class IdentityErasureTargetClient {
  private final IdentityErasureServiceGrpc.IdentityErasureServiceBlockingStub stub;

  public IdentityErasureTargetClient(ManagedChannel channel) {
    Metadata metadata = new Metadata();
    metadata.put(
        Metadata.Key.of("x-hooshix-erasure-caller", Metadata.ASCII_STRING_MARSHALLER), "web-bff");
    stub =
        IdentityErasureServiceGrpc.newBlockingStub(
            ClientInterceptors.intercept(
                channel, MetadataUtils.newAttachHeadersInterceptor(metadata)));
  }

  public UUID resolve(UUID eventId, UUID requestId, String policyVersion) {
    var response =
        stub.withDeadlineAfter(2, TimeUnit.SECONDS)
            .beginParticipantErasure(
                BeginParticipantErasureRequest.newBuilder()
                    .setEventId(eventId.toString())
                    .setErasureRequestId(requestId.toString())
                    .setParticipant(ErasureParticipant.ERASURE_PARTICIPANT_WEB_BFF)
                    .setParticipantPolicyVersion(policyVersion)
                    .build());
    if (!response.getCompletePage() || response.getUserId().isEmpty()) {
      throw new IllegalStateException("Identity returned an invalid BFF erasure target");
    }
    return UUID.fromString(response.getUserId());
  }
}
