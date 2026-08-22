package com.sajtech.notification.infrastructure.client.identity;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.contract.v1.*;
import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;
import com.sajtech.notification.application.result.port.out.NotificationResultCallbackPort;
import com.sajtech.notification.contract.v1.NotificationLifecycle;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

public final class GrpcIdentityNotificationResultClient implements NotificationResultCallbackPort {
  private static final long DEADLINE_MS = 750;
  private final ManagedChannel channel;

  public GrpcIdentityNotificationResultClient(ManagedChannel channel) {
    this.channel = channel;
  }

  @Override
  public void report(NotificationResultOutboxRecord record) {
    Timestamp occurred =
        Timestamp.newBuilder()
            .setSeconds(record.occurredAt().getEpochSecond())
            .setNanos(record.occurredAt().getNano())
            .build();
    ReportNotificationResultRequest request =
        ReportNotificationResultRequest.newBuilder()
            .setNotificationId(record.notificationId().toString())
            .setTerminalLifecycle(toContract(record.terminalLifecycle()))
            .setOccurredAt(occurred)
            .build();
    ReportNotificationResultResponse response =
        IdentityNotificationResultServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
            .reportNotificationResult(request);
    if (!response.getAccepted()) {
      throw new IllegalStateException("Identity rejected Notification terminal result");
    }
  }

  private static NotificationLifecycle toContract(
      com.sajtech.notification.domain.notification.model.NotificationLifecycle lifecycle) {
    return switch (lifecycle) {
      case DELIVERED -> NotificationLifecycle.NOTIFICATION_LIFECYCLE_DELIVERED;
      case FAILED_PERMANENT -> NotificationLifecycle.NOTIFICATION_LIFECYCLE_FAILED_PERMANENT;
      case EXPIRED -> NotificationLifecycle.NOTIFICATION_LIFECYCLE_EXPIRED;
      case DELIVERY_STATUS_UNKNOWN ->
          NotificationLifecycle.NOTIFICATION_LIFECYCLE_DELIVERY_STATUS_UNKNOWN;
      default ->
          throw new IllegalArgumentException("Notification result lifecycle must be terminal");
    };
  }
}
