package com.sajtech.identity.interfaces.password.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityPasswordGrpcService
    extends IdentityPasswordServiceGrpc.IdentityPasswordServiceImplBase {
  private final ChangePassword changePassword;
  private final RequestPasswordRecovery requestRecovery;
  private final ConfirmPasswordRecovery confirmRecovery;

  public IdentityPasswordGrpcService(
      ChangePassword changePassword,
      RequestPasswordRecovery requestRecovery,
      ConfirmPasswordRecovery confirmRecovery) {
    this.changePassword = changePassword;
    this.requestRecovery = requestRecovery;
    this.confirmRecovery = confirmRecovery;
  }

  @Override
  public void changePassword(
      ChangePasswordRequest request, StreamObserver<ChangePasswordResponse> observer) {
    try {
      PasswordChangeSession result =
          changePassword.change(
              new ChangePasswordCommand(
                  requestId(request.getRequestId()),
                  request.getRefreshCredential(),
                  request.getCurrentPassword(),
                  request.getNewPassword()));
      observer.onNext(
          ChangePasswordResponse.newBuilder()
              .setChanged(true)
              .setRefreshCredential(result.refreshCredential())
              .setRefreshIdleExpiresAt(timestamp(result.idleExpiresAt()))
              .setRefreshAbsoluteExpiresAt(timestamp(result.absoluteExpiresAt()))
              .build());
      observer.onCompleted();
    } catch (PasswordException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void requestPasswordRecovery(
      RequestPasswordRecoveryRequest request,
      StreamObserver<RequestPasswordRecoveryResponse> observer) {
    try {
      requestRecovery.request(
          new RequestPasswordRecoveryCommand(
              requestId(request.getRequestId()),
              channel(request.getChannel()),
              request.getPrimaryContact(),
              address(request.getClientAddress().getAddress())));
      observer.onNext(RequestPasswordRecoveryResponse.newBuilder().setAccepted(true).build());
      observer.onCompleted();
    } catch (PasswordException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void confirmPasswordRecovery(
      ConfirmPasswordRecoveryRequest request,
      StreamObserver<ConfirmPasswordRecoveryResponse> observer) {
    try {
      confirmRecovery.confirm(
          new ConfirmPasswordRecoveryCommand(
              requestId(request.getRequestId()),
              channel(request.getChannel()),
              request.getPrimaryContact(),
              request.getCode(),
              request.getNewPassword(),
              address(request.getClientAddress().getAddress()),
              request.hasMfaProof()
                  ? new com.sajtech.identity.application.mfa.model.MfaProof(
                      switch (request.getMfaProof().getType()) {
                        case MFA_PROOF_TYPE_TOTP ->
                            com.sajtech.identity.application.mfa.model.MfaProofType.TOTP;
                        case MFA_PROOF_TYPE_RECOVERY_CODE ->
                            com.sajtech.identity.application.mfa.model.MfaProofType.RECOVERY_CODE;
                        default -> throw new IllegalArgumentException("Unsupported MFA proof type");
                      },
                      request.getMfaProof().getCode())
                  : null));
      observer.onNext(ConfirmPasswordRecoveryResponse.newBuilder().setChanged(true).build());
      observer.onCompleted();
    } catch (PasswordException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  private static UUID requestId(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
      throw new IllegalArgumentException("Request ID is invalid");
    }
    return UUID.fromString(value);
  }

  private static RegistrationChannel channel(AuthenticationChannel value) {
    return switch (value) {
      case AUTHENTICATION_CHANNEL_EMAIL -> RegistrationChannel.EMAIL;
      case AUTHENTICATION_CHANNEL_PHONE -> RegistrationChannel.PHONE;
      default -> throw new IllegalArgumentException("Authentication channel is invalid");
    };
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

  private static Status status(PasswordException exception) {
    Status base =
        switch (exception.error()) {
          case INVALID_ARGUMENT -> Status.INVALID_ARGUMENT;
          case INVALID_CREDENTIALS, INVALID_SESSION -> Status.UNAUTHENTICATED;
          case RECENT_AUTHENTICATION_REQUIRED -> Status.FAILED_PRECONDITION;
          case INVALID_RECOVERY_PROOF, RECOVERY_PROOF_EXHAUSTED -> Status.PERMISSION_DENIED;
          case DEPENDENCY_UNAVAILABLE -> Status.UNAVAILABLE;
        };
    return base.withDescription(exception.error().name());
  }

  private static Status status(RegistrationException exception) {
    RegistrationError error = exception.error();
    Status base =
        switch (error) {
          case INVALID_ARGUMENT, UNSUPPORTED_CHANNEL -> Status.INVALID_ARGUMENT;
          case COMPROMISED_PASSWORD,
              PHONE_REGISTRATION_DISABLED,
              INVALID_VERIFICATION_CODE,
              RESEND_TOO_SOON ->
              Status.FAILED_PRECONDITION;
          case REQUEST_ID_CONFLICT -> Status.ALREADY_EXISTS;
          case QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED;
          case DEPENDENCY_UNAVAILABLE,
              QUOTA_UNAVAILABLE,
              QUOTA_TIME_SOURCE_UNHEALTHY,
              QUOTA_CAPACITY_UNHEALTHY ->
              Status.UNAVAILABLE;
        };
    return base.withDescription(error.name());
  }
}
