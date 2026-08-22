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
              UUID.fromString(request.getNotificationId()),
              terminal(request.getTerminalLifecycle()),
              Instant.ofEpochSecond(
                  request.getOccurredAt().getSeconds(), request.getOccurredAt().getNanos()));
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
}
