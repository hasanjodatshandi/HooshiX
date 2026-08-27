package com.sajtech.identity.interfaces.erasure.grpc;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import com.sajtech.identity.application.erasure.port.in.ErasureCoordination;
import com.sajtech.identity.application.erasure.port.in.LegalHoldManagement;
import com.sajtech.identity.application.erasure.port.in.ParticipantErasureCoordination;
import com.sajtech.identity.application.erasure.port.in.RequestSelfErasureCommand;
import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.application.mfa.model.MfaProofType;
import com.sajtech.identity.contract.v1.ErasureRequestState;
import com.sajtech.identity.contract.v1.IdentityErasureServiceGrpc;
import com.sajtech.identity.contract.v1.RequestSelfErasureRequest;
import com.sajtech.identity.contract.v1.RequestSelfErasureResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityErasureGrpcService
    extends IdentityErasureServiceGrpc.IdentityErasureServiceImplBase {
  private final ErasureCoordination service;
  private final ParticipantErasureCoordination participants;
  private final LegalHoldManagement legalHolds;

  public IdentityErasureGrpcService(
      ErasureCoordination service,
      ParticipantErasureCoordination participants,
      LegalHoldManagement legalHolds) {
    this.service = service;
    this.participants = participants;
    this.legalHolds = legalHolds;
  }

  @Override
  public void createLegalHold(
      com.sajtech.identity.contract.v1.CreateLegalHoldRequest request,
      StreamObserver<com.sajtech.identity.contract.v1.CreateLegalHoldResponse> observer) {
    try {
      var result =
          legalHolds.create(
              UUID.fromString(request.getRequestId()),
              request.getRefreshCredential(),
              UUID.fromString(request.getErasureRequestId()),
              request.getAuthorityReference(),
              proof(request.getMfaProof()));
      observer.onNext(createHoldResponse(result));
      observer.onCompleted();
    } catch (ErasureException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void releaseLegalHold(
      com.sajtech.identity.contract.v1.ReleaseLegalHoldRequest request,
      StreamObserver<com.sajtech.identity.contract.v1.ReleaseLegalHoldResponse> observer) {
    try {
      var result =
          legalHolds.release(
              UUID.fromString(request.getRequestId()),
              request.getRefreshCredential(),
              UUID.fromString(request.getHoldId()),
              proof(request.getMfaProof()));
      observer.onNext(releaseHoldResponse(result));
      observer.onCompleted();
    } catch (ErasureException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void beginParticipantErasure(
      com.sajtech.identity.contract.v1.BeginParticipantErasureRequest request,
      StreamObserver<com.sajtech.identity.contract.v1.BeginParticipantErasureResponse> observer) {
    try {
      ErasureParticipant participant =
          switch (request.getParticipant()) {
            case ERASURE_PARTICIPANT_IDENTITY_SERVICE -> ErasureParticipant.IDENTITY_SERVICE;
            case ERASURE_PARTICIPANT_AUTHORIZATION_SERVICE ->
                ErasureParticipant.AUTHORIZATION_SERVICE;
            case ERASURE_PARTICIPANT_NOTIFICATION_SERVICE ->
                ErasureParticipant.NOTIFICATION_SERVICE;
            case ERASURE_PARTICIPANT_WEB_BFF -> ErasureParticipant.WEB_BFF;
            default -> throw new IllegalArgumentException("Unsupported erasure participant");
          };
      ParticipantErasureTarget target =
          participants.begin(
              UUID.fromString(request.getEventId()),
              UUID.fromString(request.getErasureRequestId()),
              participant,
              request.getParticipantPolicyVersion(),
              request.getPageToken(),
              ErasureWorkloadIdentityInterceptor.WORKLOAD.get());
      var response =
          com.sajtech.identity.contract.v1.BeginParticipantErasureResponse.newBuilder()
              .setParticipant(request.getParticipant())
              .setNextPageToken(target.nextPageToken())
              .setCompletePage(target.completePage());
      if (target.userId() != null) response.setUserId(target.userId().toString());
      target.notificationIds().forEach(id -> response.addNotificationIds(id.toString()));
      observer.onNext(response.build());
      observer.onCompleted();
    } catch (ErasureException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void requestSelfErasure(
      RequestSelfErasureRequest request, StreamObserver<RequestSelfErasureResponse> observer) {
    try {
      ErasureRequestView result =
          service.requestSelfErasure(
              new RequestSelfErasureCommand(
                  UUID.fromString(request.getRequestId()),
                  request.getRefreshCredential(),
                  request.hasMfaProof() ? proof(request) : null,
                  request.getConfirmation()));
      observer.onNext(response(result));
      observer.onCompleted();
    } catch (ErasureException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  private static MfaProof proof(RequestSelfErasureRequest request) {
    return proof(request.getMfaProof());
  }

  private static MfaProof proof(com.sajtech.identity.contract.v1.MfaProof value) {
    MfaProofType type =
        switch (value.getType()) {
          case MFA_PROOF_TYPE_TOTP -> MfaProofType.TOTP;
          case MFA_PROOF_TYPE_RECOVERY_CODE -> MfaProofType.RECOVERY_CODE;
          default -> throw new IllegalArgumentException("Unsupported MFA proof type");
        };
    return new MfaProof(type, value.getCode());
  }

  private static com.sajtech.identity.contract.v1.CreateLegalHoldResponse createHoldResponse(
      com.sajtech.identity.application.erasure.model.LegalHoldView result) {
    var response =
        com.sajtech.identity.contract.v1.CreateLegalHoldResponse.newBuilder()
            .setHoldId(result.holdId().toString())
            .setErasureRequestId(result.erasureRequestId().toString())
            .setState(
                com.sajtech.identity.contract.v1.LegalHoldState.valueOf(
                    "LEGAL_HOLD_STATE_" + result.state()))
            .setPolicyVersion(result.policyVersion())
            .setCreatedAt(timestamp(result.createdAt()));
    if (result.releasedAt() != null) response.setReleasedAt(timestamp(result.releasedAt()));
    return response.build();
  }

  private static com.sajtech.identity.contract.v1.ReleaseLegalHoldResponse releaseHoldResponse(
      com.sajtech.identity.application.erasure.model.LegalHoldView result) {
    var response =
        com.sajtech.identity.contract.v1.ReleaseLegalHoldResponse.newBuilder()
            .setHoldId(result.holdId().toString())
            .setErasureRequestId(result.erasureRequestId().toString())
            .setState(
                com.sajtech.identity.contract.v1.LegalHoldState.valueOf(
                    "LEGAL_HOLD_STATE_" + result.state()))
            .setPolicyVersion(result.policyVersion())
            .setCreatedAt(timestamp(result.createdAt()));
    if (result.releasedAt() != null) response.setReleasedAt(timestamp(result.releasedAt()));
    return response.build();
  }

  private static RequestSelfErasureResponse response(ErasureRequestView result) {
    var builder =
        RequestSelfErasureResponse.newBuilder()
            .setErasureRequestId(result.erasureRequestId().toString())
            .setState(ErasureRequestState.valueOf("ERASURE_REQUEST_STATE_" + result.state()))
            .setParticipantPolicyVersion(result.participantPolicyVersion())
            .setAcceptedAt(timestamp(result.acceptedAt()));
    if (result.completedAt() != null) builder.setCompletedAt(timestamp(result.completedAt()));
    return builder.build();
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static Status status(ErasureException exception) {
    Status base =
        switch (exception.error()) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_SESSION -> Status.UNAUTHENTICATED;
          case RECENT_AUTHENTICATION_REQUIRED, ACTIVE_MEMBERSHIP_EXISTS, LEGAL_HOLD_ACTIVE ->
              Status.FAILED_PRECONDITION;
          case MFA_PROOF_REQUIRED, MFA_PROOF_INVALID, FORBIDDEN -> Status.PERMISSION_DENIED;
          case REQUEST_CONFLICT -> Status.ALREADY_EXISTS;
          case NOT_FOUND -> Status.NOT_FOUND;
        };
    return base.withDescription(exception.error().name());
  }
}
