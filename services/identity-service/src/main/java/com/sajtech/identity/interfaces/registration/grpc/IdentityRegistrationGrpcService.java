package com.sajtech.identity.interfaces.registration.grpc;

import com.google.protobuf.ByteString;
import com.sajtech.identity.application.registration.*;
import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.application.registration.port.in.*;
import com.sajtech.identity.contract.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;

public final class IdentityRegistrationGrpcService
    extends IdentityRegistrationServiceGrpc.IdentityRegistrationServiceImplBase {
  private final RegisterLocal register;
  private final ResendRegistrationVerification resend;
  private final ConfirmRegistration confirm;

  public IdentityRegistrationGrpcService(
      RegisterLocal register, ResendRegistrationVerification resend, ConfirmRegistration confirm) {
    this.register = register;
    this.resend = resend;
    this.confirm = confirm;
  }

  @Override
  public void registerLocal(
      RegisterLocalRequest request, StreamObserver<RegisterLocalResponse> observer) {
    try {
      register.register(
          new RegisterLocalCommand(
              requestId(request.getRequestId()),
              channel(request.getChannel()),
              request.getContact(),
              request.getPassword(),
              locale(request.getLocale()),
              request.getFirstName(),
              request.getLastName(),
              request.hasFatherName() ? request.getFatherName() : null,
              address(request.getClientAddress().getAddress())));
      observer.onNext(RegisterLocalResponse.newBuilder().setAccepted(true).build());
      observer.onCompleted();
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void resendRegistrationVerification(
      ResendRegistrationVerificationRequest request,
      StreamObserver<ResendRegistrationVerificationResponse> observer) {
    try {
      resend.resend(
          new ResendRegistrationCommand(
              requestId(request.getRequestId()),
              channel(request.getChannel()),
              request.getContact(),
              address(request.getClientAddress().getAddress())));
      observer.onNext(
          ResendRegistrationVerificationResponse.newBuilder().setAccepted(true).build());
      observer.onCompleted();
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  @Override
  public void confirmRegistration(
      ConfirmRegistrationRequest request, StreamObserver<ConfirmRegistrationResponse> observer) {
    try {
      boolean ok =
          confirm.confirm(
              new ConfirmRegistrationCommand(
                  requestId(request.getRequestId()),
                  channel(request.getChannel()),
                  request.getContact(),
                  request.getCode(),
                  address(request.getClientAddress().getAddress())));
      observer.onNext(ConfirmRegistrationResponse.newBuilder().setConfirmed(ok).build());
      observer.onCompleted();
    } catch (RegistrationException exception) {
      observer.onError(status(exception).asRuntimeException());
    } catch (IllegalArgumentException exception) {
      observer.onError(
          Status.INVALID_ARGUMENT.withDescription("INVALID_ARGUMENT").asRuntimeException());
    }
  }

  private static UUID requestId(String value) {
    if (value == null
        || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))
      throw new IllegalArgumentException("Invalid request ID");
    return UUID.fromString(value);
  }

  private static com.sajtech.identity.domain.registration.valueobject.RegistrationChannel channel(
      com.sajtech.identity.contract.v1.RegistrationChannel value) {
    return switch (value) {
      case REGISTRATION_CHANNEL_EMAIL ->
          com.sajtech.identity.domain.registration.valueobject.RegistrationChannel.EMAIL;
      case REGISTRATION_CHANNEL_PHONE ->
          com.sajtech.identity.domain.registration.valueobject.RegistrationChannel.PHONE;
      default -> throw new IllegalArgumentException("Unsupported registration channel");
    };
  }

  private static com.sajtech.identity.domain.registration.valueobject.RegistrationLocale locale(
      com.sajtech.identity.contract.v1.RegistrationLocale value) {
    return switch (value) {
      case REGISTRATION_LOCALE_FA ->
          com.sajtech.identity.domain.registration.valueobject.RegistrationLocale.FA;
      case REGISTRATION_LOCALE_EN ->
          com.sajtech.identity.domain.registration.valueobject.RegistrationLocale.EN;
      default -> throw new IllegalArgumentException("Unsupported registration locale");
    };
  }

  private static byte[] address(ByteString value) {
    byte[] result = value.toByteArray();
    if (result.length != 4 && result.length != 16)
      throw new IllegalArgumentException("Trusted client address is invalid");
    return result;
  }

  private static Status status(RegistrationException exception) {
    Status base =
        switch (exception.error()) {
          case INVALID_ARGUMENT, UNSUPPORTED_CHANNEL -> Status.INVALID_ARGUMENT;
          case PHONE_REGISTRATION_DISABLED, COMPROMISED_PASSWORD -> Status.FAILED_PRECONDITION;
          case REQUEST_ID_CONFLICT -> Status.ALREADY_EXISTS;
          case QUOTA_EXCEEDED -> Status.RESOURCE_EXHAUSTED;
          case DEPENDENCY_UNAVAILABLE,
              QUOTA_UNAVAILABLE,
              QUOTA_TIME_SOURCE_UNHEALTHY,
              QUOTA_CAPACITY_UNHEALTHY ->
              Status.UNAVAILABLE;
          case INVALID_VERIFICATION_CODE, RESEND_TOO_SOON -> Status.FAILED_PRECONDITION;
        };
    return base.withDescription(exception.error().name());
  }
}
