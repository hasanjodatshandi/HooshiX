package com.sajtech.identity.interfaces.mfa.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.application.mfa.model.MfaProofType;
import com.sajtech.identity.application.mfa.model.MfaSessionMutation;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityMfaGrpcService
    extends IdentityMfaServiceGrpc.IdentityMfaServiceImplBase {
  private final MfaManagement management;
  private final CompleteMfaAuthentication completion;

  public IdentityMfaGrpcService(MfaManagement management, CompleteMfaAuthentication completion) {
    this.management = management;
    this.completion = completion;
  }

  @Override
  public void getMfaStatus(
      GetMfaStatusRequest request, StreamObserver<GetMfaStatusResponse> observer) {
    execute(
        observer,
        () -> {
          var status =
              management.status(
                  new GetMfaStatusCommand(
                      requestId(request.getRequestId()), request.getRefreshCredential()));
          return GetMfaStatusResponse.newBuilder()
              .setTotpEnabled(status.totpEnabled())
              .setRecoveryCodesRemaining(status.recoveryCodesRemaining())
              .build();
        });
  }

  @Override
  public void startTotpEnrollment(
      StartTotpEnrollmentRequest request, StreamObserver<StartTotpEnrollmentResponse> observer) {
    execute(
        observer,
        () -> {
          var started =
              management.startEnrollment(
                  new StartTotpEnrollmentCommand(
                      requestId(request.getRequestId()),
                      request.getRefreshCredential(),
                      address(request.getClientAddress().getAddress()),
                      request.hasCurrentProof() ? proof(request.getCurrentProof()) : null));
          return StartTotpEnrollmentResponse.newBuilder()
              .setEnrollmentChallenge(started.enrollmentChallenge())
              .setBase32Secret(started.base32Secret())
              .setOtpauthUri(started.otpauthUri())
              .setExpiresAt(timestamp(started.expiresAt()))
              .build();
        });
  }

  @Override
  public void confirmTotpEnrollment(
      ConfirmTotpEnrollmentRequest request,
      StreamObserver<ConfirmTotpEnrollmentResponse> observer) {
    execute(
        observer,
        () -> {
          MfaSessionMutation mutation =
              management.confirmEnrollment(
                  new ConfirmTotpEnrollmentCommand(
                      requestId(request.getRequestId()),
                      request.getRefreshCredential(),
                      request.getEnrollmentChallenge(),
                      request.getTotpCode(),
                      address(request.getClientAddress().getAddress())));
          return ConfirmTotpEnrollmentResponse.newBuilder()
              .addAllRecoveryCodes(mutation.recoveryCodes())
              .setSession(session(mutation.session()))
              .build();
        });
  }

  @Override
  public void disableTotp(
      DisableTotpRequest request, StreamObserver<DisableTotpResponse> observer) {
    execute(
        observer,
        () ->
            DisableTotpResponse.newBuilder()
                .setSession(
                    session(
                        management
                            .disable(
                                new DisableTotpCommand(
                                    requestId(request.getRequestId()),
                                    request.getRefreshCredential(),
                                    proof(request.getProof()),
                                    address(request.getClientAddress().getAddress())))
                            .session()))
                .build());
  }

  @Override
  public void rotateRecoveryCodes(
      RotateRecoveryCodesRequest request, StreamObserver<RotateRecoveryCodesResponse> observer) {
    execute(
        observer,
        () -> {
          MfaSessionMutation mutation =
              management.rotateRecoveryCodes(
                  new RotateRecoveryCodesCommand(
                      requestId(request.getRequestId()),
                      request.getRefreshCredential(),
                      proof(request.getProof()),
                      address(request.getClientAddress().getAddress())));
          return RotateRecoveryCodesResponse.newBuilder()
              .addAllRecoveryCodes(mutation.recoveryCodes())
              .setSession(session(mutation.session()))
              .build();
        });
  }

  @Override
  public void completeMfaAuthentication(
      CompleteMfaAuthenticationRequest request,
      StreamObserver<CompleteMfaAuthenticationResponse> observer) {
    execute(
        observer,
        () ->
            CompleteMfaAuthenticationResponse.newBuilder()
                .setSession(
                    session(
                        completion.complete(
                            new CompleteMfaAuthenticationCommand(
                                requestId(request.getRequestId()),
                                request.getMfaChallenge(),
                                proof(request.getProof()),
                                address(request.getClientAddress().getAddress())))))
                .build());
  }

  private static MfaProof proof(com.sajtech.identity.contract.v1.MfaProof value) {
    return new MfaProof(
        switch (value.getType()) {
          case MFA_PROOF_TYPE_TOTP -> MfaProofType.TOTP;
          case MFA_PROOF_TYPE_RECOVERY_CODE -> MfaProofType.RECOVERY_CODE;
          default -> throw new IllegalArgumentException("Unsupported MFA proof type");
        },
        value.getCode());
  }

  private static MfaSessionCredentials session(AuthenticationSession value) {
    return MfaSessionCredentials.newBuilder()
        .setIdentitySessionId(value.sessionId())
        .setRefreshFamilyId(value.refreshFamilyId().toString())
        .setRefreshCredential(value.refreshCredential())
        .setRefreshIdleExpiresAt(timestamp(value.idleExpiresAt()))
        .setRefreshAbsoluteExpiresAt(timestamp(value.absoluteExpiresAt()))
        .setSessionMode(
            switch (value.mode()) {
              case AUTHENTICATED_ONBOARDING ->
                  com.sajtech.identity.contract.v1.AuthenticationSessionMode
                      .AUTHENTICATION_SESSION_MODE_AUTHENTICATED_ONBOARDING;
              case TENANT_AUTHENTICATED ->
                  com.sajtech.identity.contract.v1.AuthenticationSessionMode
                      .AUTHENTICATION_SESSION_MODE_TENANT_AUTHENTICATED;
              case MFA_REQUIRED -> throw new IllegalArgumentException("MFA session is incomplete");
            })
        .setUserId(value.userId().toString())
        .setSelectedTenantId(
            value.selectedTenantId() == null ? "" : value.selectedTenantId().toString())
        .setSelectedMembershipId(
            value.selectedMembershipId() == null ? "" : value.selectedMembershipId().toString())
        .build();
  }

  private static UUID requestId(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Invalid request ID");
    }
    return UUID.fromString(value);
  }

  private static byte[] address(ByteString value) {
    byte[] result = value.toByteArray();
    if (result.length != 4 && result.length != 16) {
      throw new IllegalArgumentException("Trusted client address is invalid");
    }
    return result;
  }

  private static Timestamp timestamp(Instant value) {
    return Timestamp.newBuilder()
        .setSeconds(value.getEpochSecond())
        .setNanos(value.getNano())
        .build();
  }

  private static Status status(MfaException exception) {
    MfaError error = exception.error();
    Status base =
        switch (error) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_SESSION, INVALID_PROOF, REPLAYED_PROOF -> Status.UNAUTHENTICATED;
          case RECENT_AUTHENTICATION_REQUIRED,
              MFA_NOT_ENABLED,
              CHALLENGE_EXPIRED,
              CHALLENGE_EXHAUSTED ->
              Status.FAILED_PRECONDITION;
          case STATE_CONFLICT -> Status.ABORTED;
          case QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED;
          case QUOTA_UNAVAILABLE, QUOTA_TIME_SOURCE_UNHEALTHY, QUOTA_CAPACITY_UNHEALTHY ->
              Status.UNAVAILABLE;
        };
    return base.withDescription(error.name());
  }

  private static <T> void execute(StreamObserver<T> observer, java.util.function.Supplier<T> work) {
    try {
      observer.onNext(work.get());
      observer.onCompleted();
    } catch (MfaException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }
}
