package com.sajtech.identity.interfaces.notification.grpc;

import com.sajtech.identity.application.notification.model.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.notification.contract.v1.NotificationLifecycle;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;

public final class IdentityNotificationResultGrpcService
    extends IdentityNotificationResultServiceGrpc.IdentityNotificationResultServiceImplBase {
  private static final long MIN_TIMESTAMP_SECONDS = -62_135_596_800L;
  private static final long MAX_TIMESTAMP_SECONDS = 253_402_300_799L;
  private final ReportNotificationResult report;

  public IdentityNotificationResultGrpcService(ReportNotificationResult report) {
    this.report = report;
  }

  @Override
  public void reportNotificationResult(
      ReportNotificationResultRequest request,
      StreamObserver<ReportNotificationResultResponse> responseObserver) {
    try {
      NotificationTerminalResult result =
          new NotificationTerminalResult(
              canonicalUuidV4(request.getNotificationId()),
              terminal(request.getTerminalLifecycle()),
              instant(request.getOccurredAt().getSeconds(), request.getOccurredAt().getNanos()));
      NotificationResultApplyOutcome outcome = report.report(result);
      if (outcome == NotificationResultApplyOutcome.NOT_FOUND) {
        responseObserver.onError(Status.NOT_FOUND.asRuntimeException());
        return;
      }
      if (outcome == NotificationResultApplyOutcome.CONFLICT) {
        responseObserver.onError(Status.FAILED_PRECONDITION.asRuntimeException());
        return;
      }
      responseObserver.onNext(
          ReportNotificationResultResponse.newBuilder().setAccepted(true).build());
      responseObserver.onCompleted();
    } catch (IllegalArgumentException exception) {
      responseObserver.onError(Status.INVALID_ARGUMENT.asRuntimeException());
    }
  }

  private static NotificationTerminalLifecycle terminal(NotificationLifecycle lifecycle) {
    return switch (lifecycle) {
      case NOTIFICATION_LIFECYCLE_DELIVERED -> NotificationTerminalLifecycle.DELIVERED;
      case NOTIFICATION_LIFECYCLE_FAILED_PERMANENT ->
          NotificationTerminalLifecycle.FAILED_PERMANENT;
      case NOTIFICATION_LIFECYCLE_EXPIRED -> NotificationTerminalLifecycle.EXPIRED;
      case NOTIFICATION_LIFECYCLE_DELIVERY_STATUS_UNKNOWN ->
          NotificationTerminalLifecycle.DELIVERY_STATUS_UNKNOWN;
      default ->
          throw new IllegalArgumentException("Notification result lifecycle must be terminal");
    };
  }

  private static UUID canonicalUuidV4(String value) {
    UUID result = UUID.fromString(value);
    if (result.version() != 4 || !result.toString().equals(value)) {
      throw new IllegalArgumentException("Notification ID is invalid");
    }
    return result;
  }

  private static Instant instant(long seconds, int nanos) {
    if (seconds < MIN_TIMESTAMP_SECONDS
        || seconds > MAX_TIMESTAMP_SECONDS
        || nanos < 0
        || nanos > 999_999_999
        || nanos % 1_000 != 0) {
      throw new IllegalArgumentException("Timestamp is invalid");
    }
    return Instant.ofEpochSecond(seconds, nanos);
  }
}
