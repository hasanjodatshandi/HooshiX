package com.sajtech.notification.interfaces.notification.grpc;

import com.google.protobuf.Timestamp;
import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.PasswordChangedNoticeContent;
import com.sajtech.notification.application.submit.model.SemanticContent;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import com.sajtech.notification.contract.v1.NotificationServiceGrpc;
import com.sajtech.notification.contract.v1.SubmitNotificationRequest;
import com.sajtech.notification.contract.v1.SubmitNotificationResponse;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class NotificationGrpcService
    extends NotificationServiceGrpc.NotificationServiceImplBase {
  private static final Logger LOGGER = LoggerFactory.getLogger(NotificationGrpcService.class);
  private static final Metadata.Key<String> ERROR_CODE =
      Metadata.Key.of("x-hooshix-error-code", Metadata.ASCII_STRING_MARSHALLER);
  private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;

  private final SubmitNotification submitNotification;

  public NotificationGrpcService(SubmitNotification submitNotification) {
    this.submitNotification = submitNotification;
  }

  @Override
  public void submitNotification(
      SubmitNotificationRequest request,
      StreamObserver<SubmitNotificationResponse> responseObserver) {
    try {
      SubmitNotificationResult result = submitNotification.submit(toCommand(request));
      responseObserver.onNext(toResponse(result));
      responseObserver.onCompleted();
    } catch (NotificationSubmissionException exception) {
      responseObserver.onError(toStatus(exception));
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(
          status(
              Status.INVALID_ARGUMENT,
              NotificationSubmissionError.INVALID_NOTIFICATION_REQUEST.name()));
    } catch (RuntimeException exception) {
      LOGGER
          .atError()
          .addKeyValue("eventCode", "NOTIFICATION_SUBMIT_UNEXPECTED_FAILURE")
          .log("Notification submission failed");
      responseObserver.onError(
          status(Status.UNAVAILABLE, NotificationSubmissionError.NOTIFICATION_UNAVAILABLE.name()));
    }
  }

  private static SubmitNotificationCommand toCommand(SubmitNotificationRequest request) {
    return new SubmitNotificationCommand(
        parseCanonicalUuid(request.getRequestId()),
        channel(request),
        request.getRecipient(),
        request.getLocale(),
        request.hasMessageNotAfter() ? instant(request.getMessageNotAfter()) : null,
        semanticContent(request));
  }

  private static UUID parseCanonicalUuid(String value) {
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equals(value)) {
      throw new IllegalArgumentException("request_id must be canonical lowercase UUID");
    }
    return uuid;
  }

  private static NotificationChannel channel(SubmitNotificationRequest request) {
    return switch (request.getChannel()) {
      case NOTIFICATION_CHANNEL_EMAIL -> NotificationChannel.EMAIL;
      case NOTIFICATION_CHANNEL_SMS -> NotificationChannel.SMS;
      default -> throw new IllegalArgumentException("Notification channel is required");
    };
  }

  private static SemanticContent semanticContent(SubmitNotificationRequest request) {
    return switch (request.getSemanticContentCase()) {
      case REGISTRATION_VERIFICATION_CODE ->
          verification(
              NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
              request.getRegistrationVerificationCode().getCode(),
              request.getRegistrationVerificationCode().getExpiresMinutes());
      case PASSWORD_RECOVERY_CODE ->
          verification(
              NotificationSemanticType.PASSWORD_RECOVERY_CODE,
              request.getPasswordRecoveryCode().getCode(),
              request.getPasswordRecoveryCode().getExpiresMinutes());
      case MFA_VERIFICATION_CODE ->
          verification(
              NotificationSemanticType.MFA_VERIFICATION_CODE,
              request.getMfaVerificationCode().getCode(),
              request.getMfaVerificationCode().getExpiresMinutes());
      case PASSWORD_CHANGED_NOTICE -> new PasswordChangedNoticeContent();
      default -> throw new IllegalArgumentException("Notification semantic content is required");
    };
  }

  private static VerificationCodeContent verification(
      NotificationSemanticType semanticType, String code, int expiresMinutes) {
    return new VerificationCodeContent(semanticType, code, expiresMinutes);
  }

  private static Instant instant(Timestamp timestamp) {
    if (timestamp.getSeconds() < MIN_TIMESTAMP_SECONDS
        || timestamp.getSeconds() > MAX_TIMESTAMP_SECONDS
        || timestamp.getNanos() < 0
        || timestamp.getNanos() > 999_999_999
        || timestamp.getNanos() % 1_000 != 0) {
      throw new IllegalArgumentException(
          "Timestamp must be valid at canonical microsecond precision");
    }
    return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
  }

  private static SubmitNotificationResponse toResponse(SubmitNotificationResult result) {
    return SubmitNotificationResponse.newBuilder()
        .setNotificationId(result.notificationId().toString())
        .setLifecycle(contractLifecycle(result.lifecycle()))
        .setAcceptedAt(timestamp(result.acceptedAt()))
        .build();
  }

  private static com.sajtech.notification.contract.v1.NotificationLifecycle contractLifecycle(
      NotificationLifecycle lifecycle) {
    return switch (lifecycle) {
      case ACCEPTED ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_ACCEPTED;
      case DISPATCHING ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_DISPATCHING;
      case RETRY_WAIT ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_RETRY_WAIT;
      case PROVIDER_ACCEPTED ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_PROVIDER_ACCEPTED;
      case DELIVERED ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_DELIVERED;
      case FAILED_PERMANENT ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_FAILED_PERMANENT;
      case EXPIRED ->
          com.sajtech.notification.contract.v1.NotificationLifecycle.NOTIFICATION_LIFECYCLE_EXPIRED;
      case DELIVERY_STATUS_UNKNOWN ->
          com.sajtech.notification.contract.v1.NotificationLifecycle
              .NOTIFICATION_LIFECYCLE_DELIVERY_STATUS_UNKNOWN;
    };
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.newBuilder()
        .setSeconds(instant.getEpochSecond())
        .setNanos(instant.getNano())
        .build();
  }

  private static io.grpc.StatusRuntimeException toStatus(
      NotificationSubmissionException exception) {
    return switch (exception.error()) {
      case INVALID_NOTIFICATION_REQUEST, UNSUPPORTED_LOCALE ->
          status(Status.INVALID_ARGUMENT, exception.error().name());
      case REQUEST_ID_CONFLICT -> status(Status.ALREADY_EXISTS, exception.error().name());
      case TEMPLATE_NOT_ACTIVE -> status(Status.FAILED_PRECONDITION, exception.error().name());
      case NOTIFICATION_UNAVAILABLE -> status(Status.UNAVAILABLE, exception.error().name());
    };
  }

  private static io.grpc.StatusRuntimeException status(Status status, String machineCode) {
    Metadata metadata = new Metadata();
    metadata.put(ERROR_CODE, machineCode);
    return status.withDescription(machineCode).asRuntimeException(metadata);
  }
}
