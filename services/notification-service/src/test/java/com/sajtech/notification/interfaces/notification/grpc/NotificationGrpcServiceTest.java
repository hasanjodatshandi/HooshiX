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
import com.sajtech.notification.contract.v1.SubmitNotificationResponse;
import com.sajtech.notification.contract.v1.VerificationCodeContent;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NotificationGrpcServiceTest {
  private static final Metadata.Key<String> ERROR_CODE =
      Metadata.Key.of("x-hooshix-error-code", Metadata.ASCII_STRING_MARSHALLER);

  @Test
  void mapsCanonicalSubmitRequestAndReturnsAcceptedHandoff() {
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
    CapturingObserver<SubmitNotificationResponse> recorder = new CapturingObserver<>();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.completed).isTrue();
    assertThat(recorder.error).isNull();
    assertThat(recorder.values).hasSize(1);
    assertThat(recorder.values.getFirst().getNotificationId()).isEqualTo(notificationId.toString());
    assertThat(recorder.values.getFirst().getLifecycle())
        .isEqualTo(NotificationLifecycle.NOTIFICATION_LIFECYCLE_ACCEPTED);
    assertThat(captured.get().locale()).isEqualTo("en-US");
    assertThat(captured.get().messageNotAfter()).isEqualTo(Instant.parse("2026-08-16T00:10:00Z"));
  }

  @Test
  void replayReturnsStoredLifecycleInsteadOfForcingAccepted() {
    NotificationGrpcService service =
        new NotificationGrpcService(
            command ->
                new SubmitNotificationResult(
                    UUID.fromString("d9428888-122b-41e1-b85c-61c0c552a118"),
                    com.sajtech.notification.domain.notification.model.NotificationLifecycle
                        .PROVIDER_ACCEPTED,
                    Instant.parse("2026-08-16T00:00:00Z"),
                    true));
    CapturingObserver<SubmitNotificationResponse> recorder = new CapturingObserver<>();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.completed).isTrue();
    assertThat(recorder.error).isNull();
    assertThat(recorder.values.getFirst().getLifecycle())
        .isEqualTo(NotificationLifecycle.NOTIFICATION_LIFECYCLE_PROVIDER_ACCEPTED);
  }

  @Test
  void exposesOnlyStableMachineCodeForConflict() {
    NotificationGrpcService service =
        new NotificationGrpcService(
            command -> {
              throw new NotificationSubmissionException(
                  NotificationSubmissionError.REQUEST_ID_CONFLICT,
                  "sensitive recipient or request detail must not cross the boundary");
            });
    CapturingObserver<SubmitNotificationResponse> recorder = new CapturingObserver<>();

    service.submitNotification(validRequest(), recorder);

    assertThat(recorder.completed).isFalse();
    StatusRuntimeException error = (StatusRuntimeException) recorder.error;
    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(error.getStatus().getDescription()).isEqualTo("REQUEST_ID_CONFLICT");
    assertThat(Status.trailersFromThrowable(error).get(ERROR_CODE))
        .isEqualTo("REQUEST_ID_CONFLICT");
    assertThat(error.getMessage()).doesNotContain("sensitive", "recipient");
  }

  @Test
  void rejectsSubMicrosecondTimestampPrecision() {
    SubmitNotificationRequest request =
        validRequest().toBuilder()
            .setMessageNotAfter(Timestamp.newBuilder().setSeconds(1_787_018_200L).setNanos(123))
            .build();
    NotificationGrpcService service =
        new NotificationGrpcService(
            command -> {
              throw new AssertionError("invalid request must not reach application use case");
            });
    CapturingObserver<SubmitNotificationResponse> recorder = new CapturingObserver<>();

    service.submitNotification(request, recorder);

    assertThat(recorder.completed).isFalse();
    assertThat(Status.fromThrowable(recorder.error).getCode())
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

  private static final class CapturingObserver<T> implements StreamObserver<T> {
    private final List<T> values = new ArrayList<>();
    private Throwable error;
    private boolean completed;

    @Override
    public void onNext(T value) {
      values.add(value);
    }

    @Override
    public void onError(Throwable throwable) {
      error = throwable;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }
}
