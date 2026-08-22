package com.sajtech.notification.application.delivery.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.out.*;
import com.sajtech.notification.application.delivery.service.*;
import com.sajtech.notification.domain.notification.model.*;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RunReconciliationBatchServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-22T20:00:00Z");

  @Test
  void deliveredObservationTerminatesAndPendingObservationReschedules() {
    RecordingReconciliation delivered = new RecordingReconciliation(claim(NOW.minusSeconds(30)));
    service(delivered, ProviderReconciliationStatus.DELIVERED, NOW).run(25);
    assertThat(delivered.action).isEqualTo("DELIVERED");

    RecordingReconciliation pending = new RecordingReconciliation(claim(NOW.minusSeconds(30)));
    service(pending, ProviderReconciliationStatus.PENDING, NOW).run(25);
    assertThat(pending.action).isEqualTo("RESCHEDULE");
  }

  @Test
  void closedObservationWindowBecomesUnknownWithoutProviderCall() {
    RecordingReconciliation repository =
        new RecordingReconciliation(
            claim(NOW.minus(NotificationChannel.EMAIL.observationWindow())));
    CountingProvider email =
        new CountingProvider(NotificationChannel.EMAIL, ProviderReconciliationStatus.DELIVERED);
    var service =
        new RunReconciliationBatchService(
            repository,
            () -> NOW,
            new ProviderAttemptPlanner(new ProviderRetryPolicy()),
            new ProviderObservationPolicy(),
            List.of(
                email,
                new CountingProvider(
                    NotificationChannel.SMS, ProviderReconciliationStatus.PENDING)));

    service.run(25);
    assertThat(repository.action).isEqualTo("UNKNOWN");
    assertThat(email.calls).isZero();
  }

  @Test
  void providerExceptionRemainsInReconciliationAndNeverRedispatches() {
    RecordingReconciliation repository = new RecordingReconciliation(claim(NOW.minusSeconds(30)));
    var service =
        new RunReconciliationBatchService(
            repository,
            () -> NOW,
            new ProviderAttemptPlanner(new ProviderRetryPolicy()),
            new ProviderObservationPolicy(),
            List.of(
                new ThrowingProvider(NotificationChannel.EMAIL),
                new CountingProvider(
                    NotificationChannel.SMS, ProviderReconciliationStatus.PENDING)));

    service.run(25);
    assertThat(repository.action).isEqualTo("RESCHEDULE");
  }

  private static RunReconciliationBatchService service(
      RecordingReconciliation repository, ProviderReconciliationStatus status, Instant now) {
    return new RunReconciliationBatchService(
        repository,
        () -> now,
        new ProviderAttemptPlanner(new ProviderRetryPolicy()),
        new ProviderObservationPolicy(),
        List.of(
            new CountingProvider(NotificationChannel.EMAIL, status),
            new CountingProvider(NotificationChannel.SMS, ProviderReconciliationStatus.PENDING)));
  }

  private static DeliveryReconciliationClaim claim(Instant started) {
    return new DeliveryReconciliationClaim(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        NotificationChannel.EMAIL,
        NotificationLifecycle.PROVIDER_ACCEPTED,
        started,
        "corr");
  }

  private static final class RecordingReconciliation implements DeliveryReconciliationRepository {
    private final DeliveryReconciliationClaim claim;
    private String action;

    RecordingReconciliation(DeliveryReconciliationClaim claim) {
      this.claim = claim;
    }

    public int recoverStaleDispatches(int batch) {
      return 0;
    }

    public List<DeliveryReconciliationClaim> claimDue(int batch, Duration lease) {
      return List.of(claim);
    }

    public void recordDelivered(DeliveryReconciliationClaim c, ProviderReconciliationOutcome o) {
      action = "DELIVERED";
    }

    public void recordPermanentFailure(
        DeliveryReconciliationClaim c, ProviderReconciliationOutcome o) {
      action = "PERMANENT";
    }

    public void reschedule(
        DeliveryReconciliationClaim c, ProviderReconciliationOutcome o, Duration d) {
      action = "RESCHEDULE";
    }

    public void recordStatusUnknown(
        DeliveryReconciliationClaim c, ProviderReconciliationOutcome o) {
      action = "UNKNOWN";
    }
  }

  private static class CountingProvider implements NotificationProviderGateway {
    private final NotificationChannel channel;
    private final ProviderReconciliationStatus status;
    int calls;

    CountingProvider(NotificationChannel channel, ProviderReconciliationStatus status) {
      this.channel = channel;
      this.status = status;
    }

    public NotificationChannel channel() {
      return channel;
    }

    public boolean liveDelivery() {
      return true;
    }

    public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
      return ProviderDispatchOutcome.live(
          ProviderAttemptClassification.DEFINITIVE_ACCEPTED, null, "corr");
    }

    public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
      calls++;
      return ProviderReconciliationOutcome.live(status, null, "corr");
    }
  }

  private static final class ThrowingProvider extends CountingProvider {
    ThrowingProvider(NotificationChannel channel) {
      super(channel, ProviderReconciliationStatus.PENDING);
    }

    @Override
    public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
      throw new IllegalStateException("provider unavailable");
    }
  }
}
