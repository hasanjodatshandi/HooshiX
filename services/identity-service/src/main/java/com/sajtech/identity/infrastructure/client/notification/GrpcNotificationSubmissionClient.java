package com.sajtech.identity.infrastructure.client.notification;

import com.google.protobuf.Timestamp;
import com.sajtech.identity.application.notification.model.NotificationOutboxRecord;
import com.sajtech.identity.application.notification.port.out.NotificationSubmissionPort;
import com.sajtech.identity.application.registration.model.DecryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.notification.contract.v1.*;
import io.grpc.ManagedChannel;
import java.util.concurrent.TimeUnit;

public final class GrpcNotificationSubmissionClient implements NotificationSubmissionPort {
  private static final long DEADLINE_MS = 900;
  private final ManagedChannel channel;

  public GrpcNotificationSubmissionClient(ManagedChannel channel) {
    this.channel = channel;
  }

  @Override
  public java.util.UUID submit(NotificationOutboxRecord record, DecryptedHandoff handoff) {
    Timestamp notAfter =
        Timestamp.newBuilder()
            .setSeconds(record.messageNotAfter().getEpochSecond())
            .setNanos(record.messageNotAfter().getNano())
            .build();
    VerificationCodeContent content =
        VerificationCodeContent.newBuilder().setCode(handoff.code()).setExpiresMinutes(10).build();
    SubmitNotificationRequest.Builder request =
        SubmitNotificationRequest.newBuilder()
            .setRequestId(record.requestId().toString())
            .setChannel(
                handoff.channel() == RegistrationChannel.EMAIL
                    ? NotificationChannel.NOTIFICATION_CHANNEL_EMAIL
                    : NotificationChannel.NOTIFICATION_CHANNEL_SMS)
            .setRecipient(handoff.recipient())
            .setLocale(handoff.locale().canonical())
            .setMessageNotAfter(notAfter);
    switch (record.contentType()) {
      case REGISTRATION_VERIFICATION -> request.setRegistrationVerificationCode(content);
      case PASSWORD_RECOVERY -> request.setPasswordRecoveryCode(content);
      case CONTACT_VERIFICATION -> request.setContactVerificationCode(content);
    }
    SubmitNotificationResponse response =
        NotificationServiceGrpc.newBlockingStub(channel)
            .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
            .submitNotification(request.build());
    if (response.getLifecycle() != NotificationLifecycle.NOTIFICATION_LIFECYCLE_ACCEPTED
        && response.getLifecycle() != NotificationLifecycle.NOTIFICATION_LIFECYCLE_DISPATCHING
        && response.getLifecycle() != NotificationLifecycle.NOTIFICATION_LIFECYCLE_RETRY_WAIT
        && response.getLifecycle() != NotificationLifecycle.NOTIFICATION_LIFECYCLE_PROVIDER_ACCEPTED
        && response.getLifecycle() != NotificationLifecycle.NOTIFICATION_LIFECYCLE_DELIVERED) {
      throw new IllegalStateException("Notification returned an incompatible acceptance lifecycle");
    }
    try {
      return java.util.UUID.fromString(response.getNotificationId());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Notification returned an invalid identifier", exception);
    }
  }
}
