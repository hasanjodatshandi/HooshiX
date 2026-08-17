package com.sajtech.notification.interfaces.notification.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;
import com.sajtech.notification.contract.v1.NotificationChannel;
import com.sajtech.notification.contract.v1.NotificationLifecycle;
import com.sajtech.notification.contract.v1.SubmitNotificationRequest;
import com.sajtech.notification.contract.v1.VerificationCodeContent;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamRecorder;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NotificationGrpcServiceTest {
  private static final Metadata.Key<String> ERROR_CODE =
      Metadata.Key.of("x-hooshix-error-code", Metadata.ASCII_STRING_MARSHALLER);

  @Test
  void mapsCanonicalSubmitRequestAndReturnsAcceptedHandoff() throws Exception {
    AtomicReference<SubmitNotificationCommand> captured = new AtomicReference<>();
    UUID notificationId = UUID.fromString("d9428888-122b-41e1-b85c-61c0c552a118");
    Instant acceptedAt = Instant.parse("2026-08-16T00:00:00.123456Z");
    NotificationGrpcService service =
        new NotificationGrpcService(
            command -> {
              captured.set(command);
              return new SubmitNotificationResult(
                  notificationId,
                  com.sajtech.notification.domain.notification.model.NotificationLifecycle.ACCEPTED,
                  acceptedAt,
                  false);
            });
    StreamRecorder<com.sajtech.notification.contract.v1.SubmitNotificationResponse> recorder =
        StreamRecorder.create();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.awaitCompletion(1, TimeUnit.SECONDS)).isTrue();
    assertThat(recorder.getError()).isNull();
    assertThat(recorder.getValues()).hasSize(1);
    assertThat(recorder.getValues().getFirst().getNotificationId())
        .isEqualTo(notificationId.toString());
    assertThat(recorder.getValues().getFirst().getLifecycle())
        .isEqualTo(NotificationLifecycle.NOTIFICATION_LIFECYCLE_ACCEPTED);
    assertThat(captured.get().locale()).isEqualTo("en-US");
    assertThat(captured.get().messageNotAfter()).isEqualTo(Instant.parse("2026-08-16T00:10:00Z"));
  }

  @Test
  void replayReturnsStoredLifecycleInsteadOfForcingAccepted() throws Exception {
    NotificationGrpcService service =
        new NotificationGrpcService(
            command ->
                new SubmitNotificationResult(
                    UUID.fromString("d9428888-122b-41e1-b85c-61c0c552a118"),
                    com.sajtech.notification.domain.notification.model.NotificationLifecycle
                        .PROVIDER_ACCEPTED,
                    Instant.parse("2026-08-16T00:00:00Z"),
                    true));
    StreamRecorder<com.sajtech.notification.contract.v1.SubmitNotificationResponse> recorder =
        StreamRecorder.create();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.awaitCompletion(1, TimeUnit.SECONDS)).isTrue();
    assertThat(recorder.getError()).isNull();
    assertThat(recorder.getValues().getFirst().getLifecycle())
        .isEqualTo(NotificationLifecycle.NOTIFICATION_LIFECYCLE_PROVIDER_ACCEPTED);
  }

  @Test
  void exposesOnlyStableMachineCodeForConflict() throws Exception {
    NotificationGrpcService service =
        new NotificationGrpcService(
            command -> {
              throw new NotificationSubmissionException(
                  NotificationSubmissionError.REQUEST_ID_CONFLICT,
                  "sensitive recipient or request detail must not cross the boundary");
            });
    StreamRecorder<com.sajtech.notification.contract.v1.SubmitNotificationResponse> recorder =
        StreamRecorder.create();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.awaitCompletion(1, TimeUnit.SECONDS)).isTrue();
    StatusRuntimeException error = (StatusRuntimeException) recorder.getError();
    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(error.getStatus().getDescription()).isEqualTo("REQUEST_ID_CONFLICT");
    assertThat(Status.trailersFromThrowable(error).get(ERROR_CODE))
        .isEqualTo("REQUEST_ID_CONFLICT");
    assertThat(error.getMessage()).doesNotContain("sensitive", "recipient");
  }

  @Test
  void rejectsSubMicrosecondTimestampPrecision() throws Exception {
    SubmitNotificationRequest request =
        validRequest().toBuilder()
            .setMessageNotAfter(Timestamp.newBuilder().setSeconds(1_787_018_200L).setNanos(123))
            .build();
    NotificationGrpcService service =
        new NotificationGrpcService(
            command -> {
              throw new AssertionError("invalid request must not reach application use case");
            });
    StreamRecorder<com.sajtech.notification.contract.v1.SubmitNotificationResponse> recorder =
        StreamRecorder.create();

    service.submitNotification(request, recorder);

    assertThat(recorder.awaitCompletion(1, TimeUnit.SECONDS)).isTrue();
    assertThat(Status.fromThrowable(recorder.getError()).getCode())
        .isEqualTo(Status.Code.INVALID_ARGUMENT);
  }

  private static SubmitNotificationRequest validRequest() {
    return SubmitNotificationRequest.newBuilder()
        .setRequestId("550e8400-e29b-41d4-a716-446655440000")
        .setChannel(NotificationChannel.NOTIFICATION_CHANNEL_EMAIL)
        .setRecipient("person@example.com")
        .setLocale("en-US")
        .setMessageNotAfter(Timestamp.newBuilder().setSeconds(1_787_018_200L))
        .setRegistrationVerificationCode(
            VerificationCodeContent.newBuilder().setCode("12345678").setExpiresMinutes(10))
        .build();
  }
}
