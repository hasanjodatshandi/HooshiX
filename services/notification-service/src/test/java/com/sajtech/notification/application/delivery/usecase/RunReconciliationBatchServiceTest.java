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
    assertThat(delivered.claimSizes).containsOnly(1);

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

  @Test
  void claimsNextReconciliationOnlyAfterPriorProviderCallAndDurableCompletion() {
    List<DeliveryReconciliationClaim> claims =
        List.of(claim(NOW.minusSeconds(30)), claim(NOW.minusSeconds(30)));
    List<String> events = new ArrayList<>();
    SequencedReconciliation repository = new SequencedReconciliation(claims, events);
    SequencedProvider provider = new SequencedProvider(claims, events);
    var service =
        new RunReconciliationBatchService(
            repository,
            () -> NOW,
            new ProviderAttemptPlanner(new ProviderRetryPolicy()),
            new ProviderObservationPolicy(),
            List.of(
                provider,
                new CountingProvider(
                    NotificationChannel.SMS, ProviderReconciliationStatus.PENDING)));

    assertThat(service.run(25)).isEqualTo(new ReconciliationBatchResult(0, 2, 2));
    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
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
    private final List<Integer> claimSizes = new ArrayList<>();
    private boolean claimed;
    private String action;

    RecordingReconciliation(DeliveryReconciliationClaim claim) {
      this.claim = claim;
    }

    public int recoverStaleDispatches(int batch) {
      return 0;
    }

    public List<DeliveryReconciliationClaim> claimDue(int batch, Duration lease) {
      claimSizes.add(batch);
      if (claimed) return List.of();
      claimed = true;
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

  private static final class SequencedReconciliation implements DeliveryReconciliationRepository {
    private final List<DeliveryReconciliationClaim> claims;
    private final List<String> events;
    private int next;

    SequencedReconciliation(List<DeliveryReconciliationClaim> claims, List<String> events) {
      this.claims = claims;
      this.events = events;
    }

    public int recoverStaleDispatches(int batch) {
      return 0;
    }

    public List<DeliveryReconciliationClaim> claimDue(int batch, Duration lease) {
      assertThat(batch).isEqualTo(1);
      events.add("claim-" + next);
      return next < claims.size() ? List.of(claims.get(next++)) : List.of();
    }

    public void recordDelivered(
        DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {
      events.add("complete-" + claims.indexOf(claim));
    }

    public void recordPermanentFailure(
        DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {}

    public void reschedule(
        DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome, Duration delay) {}

    public void recordStatusUnknown(
        DeliveryReconciliationClaim claim, ProviderReconciliationOutcome outcome) {}
  }

  private static final class SequencedProvider implements NotificationProviderGateway {
    private final List<DeliveryReconciliationClaim> claims;
    private final List<String> events;

    SequencedProvider(List<DeliveryReconciliationClaim> claims, List<String> events) {
      this.claims = claims;
      this.events = events;
    }

    public NotificationChannel channel() {
      return NotificationChannel.EMAIL;
    }

    public boolean liveDelivery() {
      return true;
    }

    public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
      throw new UnsupportedOperationException();
    }

    public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
      int index =
          claims.stream()
              .map(DeliveryReconciliationClaim::notificationId)
              .toList()
              .indexOf(request.notificationId());
      events.add("remote-" + index);
      return ProviderReconciliationOutcome.live(
          ProviderReconciliationStatus.DELIVERED, null, request.providerCorrelationId());
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
