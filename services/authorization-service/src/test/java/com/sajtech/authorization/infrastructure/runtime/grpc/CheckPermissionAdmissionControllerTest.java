package com.sajtech.authorization.infrastructure.runtime.grpc;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics;
import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics.ShedReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CheckPermissionAdmissionControllerTest {
  @Test
  void perCallerFairShareDoesNotLetOneCallerConsumeAnotherCallersSlot() {
    var controller = controller(2, 1, 2, 1, Duration.ofMillis(1));
    try (var alice = controller.acquire("caller-a");
        var bob = controller.acquire("caller-b")) {
      assertThatThrownBy(() -> controller.acquire("caller-a"))
          .isInstanceOfSatisfying(
              CheckPermissionAdmissionController.AdmissionRejected.class,
              e -> assertThat(e.reason()).isEqualTo(ShedReason.QUEUE_TIMEOUT));
    }
  }

  @Test
  void globalSaturationShedsWithinConfiguredQueueWait() {
    var controller = controller(1, 1, 1, 1, Duration.ofMillis(5));
    try (var lease = controller.acquire("caller-a")) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> controller.acquire("caller-b"))
          .isInstanceOfSatisfying(
              CheckPermissionAdmissionController.AdmissionRejected.class,
              e -> assertThat(e.reason()).isEqualTo(ShedReason.QUEUE_TIMEOUT));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(100));
    }
  }

  @Test
  void missingCallerContextFailsClosedWithoutEnteringCapacity() {
    var meters = new SimpleMeterRegistry();
    var controller =
        new CheckPermissionAdmissionController(
            2, 1, 2, 1, 4, Duration.ofMillis(1), new AuthorizationCheckPermissionMetrics(meters));
    assertThatThrownBy(() -> controller.acquire(null))
        .isInstanceOfSatisfying(
            CheckPermissionAdmissionController.AdmissionRejected.class,
            e -> assertThat(e.reason()).isEqualTo(ShedReason.CALLER_CONTEXT));
    assertThat(meters.get("authorization.check_permission.in_flight").gauge().value()).isZero();
  }

  @Test
  void releasedCallerBucketDoesNotExhaustBoundedCallerRegistry() {
    var controller =
        new CheckPermissionAdmissionController(
            1,
            1,
            1,
            1,
            1,
            Duration.ofMillis(1),
            new AuthorizationCheckPermissionMetrics(new SimpleMeterRegistry()));

    try (var ignored = controller.acquire("caller-a")) {
      // Keep the first caller active long enough to prove the bucket limit is enforced.
      assertThatThrownBy(() -> controller.acquire("caller-b"))
          .isInstanceOfSatisfying(
              CheckPermissionAdmissionController.AdmissionRejected.class,
              e -> assertThat(e.reason()).isEqualTo(ShedReason.CALLER_CONTEXT));
    }

    assertThatCode(() -> controller.acquire("caller-b").close()).doesNotThrowAnyException();
  }

  @Test
  void configurationRejectsQueueWaitAboveTwentyFiveMilliseconds() {
    assertThatThrownBy(() -> controller(2, 1, 2, 1, Duration.ofMillis(26)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CheckPermissionAdmissionController controller(
      int global, int perCaller, int globalQueue, int perCallerQueue, Duration wait) {
    return new CheckPermissionAdmissionController(
        global,
        perCaller,
        globalQueue,
        perCallerQueue,
        8,
        wait,
        new AuthorizationCheckPermissionMetrics(new SimpleMeterRegistry()));
  }
}
