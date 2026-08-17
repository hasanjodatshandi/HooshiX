package com.sajtech.notification.interfaces.notification.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Timestamp;
import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.SubmitNotificationResult;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import com.sajtech.notification.proto.v1.NotificationChannel;
import com.sajtech.notification.proto.v1.RegistrationVerificationCode;
import com.sajtech.notification.proto.v1.SubmitNotificationRequest;
import com.sajtech.notification.proto.v1.SubmitNotificationResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class NotificationGrpcServiceTest {
  @Test
  void acceptsCanonicalRegistrationRequest() {
    UUID notificationId = UUID.fromString("11111111-1111-4111-8111-111111111111");
    Instant acceptedAt = Instant.parse("2026-08-16T00:00:00Z");
    SubmitNotification useCase =
        command ->
            new SubmitNotificationResult(
                notificationId, NotificationLifecycle.ACCEPTED, acceptedAt, false);
    var service = new NotificationGrpcService(useCase);
    var observer = new CapturingObserver();

    service.submitNotification(request(), observer);

    assertThat(observer.error.get()).isNull();
    assertThat(observer.response.get().getNotificationId()).isEqualTo(notificationId.toString());
    assertThat(observer.response.get().getState()).isEqualTo("ACCEPTED");
  }

  @Test
  void mapsRequestIdentityConflictWithoutReflectingSensitiveInput() {
    SubmitNotification useCase =
        command -> {
          throw new NotificationSubmissionException(
              NotificationSubmissionError.REQUEST_ID_CONFLICT, "sensitive-provider-details");
        };
    var service = new NotificationGrpcService(useCase);
    var observer = new CapturingObserver();

    service.submitNotification(request(), observer);

    assertThat(Status.fromThrowable(observer.error.get()).getCode())
        .isEqualTo(Status.Code.ALREADY_EXISTS);
    assertThat(Status.fromThrowable(observer.error.get()).getDescription())
        .isEqualTo("REQUEST_ID_CONFLICT");
  }

  private static SubmitNotificationRequest request() {
    return SubmitNotificationRequest.newBuilder()
        .setRequestId("550e8400-e29b-41d4-a716-446655440000")
        .setChannel(NotificationChannel.EMAIL)
        .setRecipient("person@example.com")
        .setLocale("en-US")
        .setMessageNotAfter(Timestamp.newBuilder().setSeconds(1786839000).build())
        .setRegistrationVerificationCode(
            RegistrationVerificationCode.newBuilder().setCode("12345678").setExpiresMinutes(10))
        .build();
  }

  private static final class CapturingObserver
      implements StreamObserver<SubmitNotificationResponse> {
    private final AtomicReference<SubmitNotificationResponse> response = new AtomicReference<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();

    @Override
    public void onNext(SubmitNotificationResponse value) {
      response.set(value);
    }

    @Override
    public void onError(Throwable throwable) {
      error.set(throwable);
    }

    @Override
    public void onCompleted() {}
  }
}
