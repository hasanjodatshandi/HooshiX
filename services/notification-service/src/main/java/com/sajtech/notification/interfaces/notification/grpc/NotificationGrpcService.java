package com.sajtech.notification.interfaces.notification.grpc;

import com.google.protobuf.Timestamp;
import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.PasswordChangedNoticeContent;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import com.sajtech.notification.proto.v1.NotificationServiceGrpc;
import com.sajtech.notification.proto.v1.SubmitNotificationRequest;
import com.sajtech.notification.proto.v1.SubmitNotificationResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class NotificationGrpcService extends NotificationServiceGrpc.NotificationServiceImplBase {
  private final SubmitNotification submitNotification;

  public NotificationGrpcService(SubmitNotification submitNotification) {
    this.submitNotification = submitNotification;
  }

  @Override
  public void submitNotification(
      SubmitNotificationRequest request,
      StreamObserver<SubmitNotificationResponse> responseObserver) {
    try {
      SubmitNotificationCommand command = toCommand(request);
      var result = submitNotification.submit(command);
      SubmitNotificationResponse response =
          SubmitNotificationResponse.newBuilder()
              .setNotificationId(result.notificationId().toString())
              .setState(result.lifecycle().name())
              .setAcceptedAt(toTimestamp(result.acceptedAt()))
              .setReplay(result.replay())
              .build();
      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (NotificationSubmissionException domainFailure) {
      responseObserver.onError(toStatus(domainFailure));
    } catch (IllegalArgumentException invalidRequest) {
      responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Invalid notification request").asRuntimeException());
    } catch (RuntimeException unavailable) {
      responseObserver.onError(Status.UNAVAILABLE.withDescription("Notification service unavailable").asRuntimeException());
    }
  }

  private static SubmitNotificationCommand toCommand(SubmitNotificationRequest request) {
    UUID requestId;
    try {
      requestId = UUID.fromString(request.getRequestId());
    } catch (IllegalArgumentException invalidUuid) {
      throw new IllegalArgumentException("Invalid notification request id", invalidUuid);
    }
    NotificationChannel channel =
        switch (request.getChannel()) {
          case EMAIL -> NotificationChannel.EMAIL;
          case SMS -> NotificationChannel.SMS;
          case UNRECOGNIZED, NOTIFICATION_CHANNEL_UNSPECIFIED ->
              throw new IllegalArgumentException("Notification channel is required");
        };
    Instant messageNotAfter = request.hasMessageNotAfter() ? toInstant(request.getMessageNotAfter()) : null;
    return new SubmitNotificationCommand(
        requestId,
        channel,
        request.getRecipient(),
        request.getLocale(),
        messageNotAfter,
        semanticContent(request));
  }

  private static com.sajtech.notification.application.submit.model.SemanticContent semanticContent(
      SubmitNotificationRequest request) {
    return switch (request.getSemanticContentCase()) {
      case REGISTRATION_VERIFICATION_CODE -> {
        var payload = request.getRegistrationVerificationCode();
        yield new VerificationCodeContent(
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            payload.getCode(),
            positiveMinutes(payload.getExpiresMinutes()));
      }
      case LOGIN_VERIFICATION_CODE -> {
        var payload = request.getLoginVerificationCode();
        yield new VerificationCodeContent(
            NotificationSemanticType.LOGIN_VERIFICATION_CODE,
            payload.getCode(),
            positiveMinutes(payload.getExpiresMinutes()));
      }
      case PASSWORD_RESET_VERIFICATION_CODE -> {
        var payload = request.getPasswordResetVerificationCode();
        yield new VerificationCodeContent(
            NotificationSemanticType.PASSWORD_RESET_VERIFICATION_CODE,
            payload.getCode(),
            positiveMinutes(payload.getExpiresMinutes()));
      }
      case PASSWORD_CHANGED_NOTICE ->
          new PasswordChangedNoticeContent(NotificationSemanticType.PASSWORD_CHANGED_NOTICE);
      case SEMANTICCONTENT_NOT_SET ->
          throw new IllegalArgumentException("Notification semantic content is required");
    };
  }

  private static int positiveMinutes(int value) {
    if (value <= 0) {
      throw new IllegalArgumentException("Verification code expiry is required");
    }
    return value;
  }

  private static Instant toInstant(Timestamp timestamp) {
    try {
      return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    } catch (RuntimeException invalidTimestamp) {
      throw new IllegalArgumentException("Invalid notification timestamp", invalidTimestamp);
    }
  }

  private static Timestamp toTimestamp(Instant instant) {
    return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
  }

  private static StatusRuntimeException toStatus(NotificationSubmissionException failure) {
    NotificationSubmissionError error = failure.error();
    Status status =
        switch (error) {
          case INVALID_NOTIFICATION_REQUEST -> Status.INVALID_ARGUMENT;
          case TEMPLATE_NOT_ACTIVE -> Status.FAILED_PRECONDITION;
          case REQUEST_ID_CONFLICT -> Status.ALREADY_EXISTS;
          case NOTIFICATION_UNAVAILABLE -> Status.UNAVAILABLE;
        };
    return status.withDescription(error.name()).asRuntimeException();
  }
}
