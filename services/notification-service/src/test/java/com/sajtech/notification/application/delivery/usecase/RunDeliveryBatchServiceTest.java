package com.sajtech.notification.application.delivery.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.notification.application.delivery.model.*;
import com.sajtech.notification.application.delivery.port.out.*;
import com.sajtech.notification.application.delivery.service.*;
import com.sajtech.notification.domain.notification.model.*;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import java.time.*;
import java.util.*;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class RunDeliveryBatchServiceTest {
  private static final Instant NOW = Instant.parse("2026-08-22T20:00:00Z");

  @Test
  void acceptedProviderOutcomeMovesAttemptToProviderAcceptedObservation() {
    RecordingAttempts attempts = new RecordingAttempts(claim());
    var service =
        service(attempts, outcome(ProviderAttemptClassification.DEFINITIVE_ACCEPTED), false);

    assertThat(service.run(25)).isEqualTo(new DeliveryBatchResult(1, 1));
    assertThat(attempts.action).isEqualTo("ACCEPTED");
    assertThat(attempts.claimSizes).containsOnly(1);
  }

  @Test
  void transientFailureSchedulesNextAttemptButProviderExceptionNeverBlindRetries() {
    RecordingAttempts transientAttempts = new RecordingAttempts(claim());
    service(
            transientAttempts,
            outcome(ProviderAttemptClassification.DEFINITIVE_TRANSIENT_FAILURE),
            false)
        .run(25);
    assertThat(transientAttempts.action).isEqualTo("RETRY");

    RecordingAttempts ambiguousAttempts = new RecordingAttempts(claim());
    service(ambiguousAttempts, null, true).run(25);
    assertThat(ambiguousAttempts.action).isEqualTo("AMBIGUOUS");
  }

  @Test
  void escrowAuthenticationFailureTerminatesLocallyBeforeProviderIo() {
    RecordingAttempts attempts = new RecordingAttempts(claim());
    var service =
        new RunDeliveryBatchService(
            attempts,
            envelope -> {
              throw new IllegalStateException("tampered");
            },
            () -> NOW,
            new ProviderAttemptPlanner(new ProviderRetryPolicy()),
            new ProviderObservationPolicy(),
            new FixedRandom(),
            providers(outcome(ProviderAttemptClassification.DEFINITIVE_ACCEPTED), false));

    service.run(25);
    assertThat(attempts.action).isEqualTo("LOCAL_FAILURE");
  }

  @Test
  void claimsNextAttemptOnlyAfterPriorProviderCallAndDurableCompletion() {
    List<DeliveryAttemptClaim> claims = List.of(claim(), claim());
    List<String> events = new ArrayList<>();
    SequencedAttempts attempts = new SequencedAttempts(claims, events);
    SequencedProvider provider = new SequencedProvider(claims, events);
    var service =
        new RunDeliveryBatchService(
            attempts,
            envelope -> new DecryptedDeliveryPayload("person@example.com", "subject", "text", null),
            () -> NOW,
            new ProviderAttemptPlanner(new ProviderRetryPolicy()),
            new ProviderObservationPolicy(),
            new FixedRandom(),
            List.of(
                provider,
                new FakeProvider(
                    NotificationChannel.SMS,
                    outcome(ProviderAttemptClassification.DEFINITIVE_ACCEPTED),
                    false)));

    assertThat(service.run(25)).isEqualTo(new DeliveryBatchResult(2, 2));
    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
  }

  private static RunDeliveryBatchService service(
      RecordingAttempts attempts, ProviderDispatchOutcome outcome, boolean failDispatch) {
    return new RunDeliveryBatchService(
        attempts,
        envelope -> new DecryptedDeliveryPayload("person@example.com", "subject", "text", null),
        () -> NOW,
        new ProviderAttemptPlanner(new ProviderRetryPolicy()),
        new ProviderObservationPolicy(),
        new FixedRandom(),
        providers(outcome, failDispatch));
  }

  private static List<NotificationProviderGateway> providers(
      ProviderDispatchOutcome outcome, boolean failDispatch) {
    return List.of(
        new FakeProvider(NotificationChannel.EMAIL, outcome, failDispatch),
        new FakeProvider(NotificationChannel.SMS, outcome, failDispatch));
  }

  private static ProviderDispatchOutcome outcome(ProviderAttemptClassification classification) {
    return ProviderDispatchOutcome.live(classification, "P200", "corr");
  }

  private static DeliveryAttemptClaim claim() {
    UUID notificationId = UUID.randomUUID();
    DeliveryEscrowEnvelope envelope =
        new DeliveryEscrowEnvelope(
            notificationId,
            "identity-service",
            UUID.randomUUID(),
            NotificationChannel.EMAIL,
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            UUID.randomUUID(),
            1,
            "v1",
            new DeliveryCiphertext(new byte[12], new byte[16]),
            null,
            new DeliveryCiphertext(new byte[12], new byte[16]),
            null);
    return new DeliveryAttemptClaim(
        notificationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        NotificationChannel.EMAIL,
        NOW.plusSeconds(300),
        envelope);
  }

  private static final class RecordingAttempts implements DeliveryAttemptRepository {
    private final DeliveryAttemptClaim claim;
    private final List<Integer> claimSizes = new ArrayList<>();
    private boolean claimed;
    private String action;

    RecordingAttempts(DeliveryAttemptClaim claim) {
      this.claim = claim;
    }

    public List<DeliveryAttemptClaim> claimDue(int batch, Duration lease) {
      claimSizes.add(batch);
      if (claimed) return List.of();
      claimed = true;
      return List.of(claim);
    }

    public void recordProviderAccepted(
        DeliveryAttemptClaim c, ProviderDispatchOutcome o, Duration d) {
      action = "ACCEPTED";
    }

    public void recordTransientRetry(
        DeliveryAttemptClaim c, ProviderDispatchOutcome o, Duration d) {
      action = "RETRY";
    }

    public void recordAmbiguous(DeliveryAttemptClaim c, ProviderDispatchOutcome o, Duration d) {
      action = "AMBIGUOUS";
    }

    public void recordPermanentFailure(DeliveryAttemptClaim c, ProviderDispatchOutcome o) {
      action = "PERMANENT";
    }

    public void recordLocalPermanentFailure(DeliveryAttemptClaim c) {
      action = "LOCAL_FAILURE";
    }

    public void recordExpired(DeliveryAttemptClaim c, ProviderDispatchOutcome o) {
      action = "EXPIRED";
    }
  }

  private static final class SequencedAttempts implements DeliveryAttemptRepository {
    private final List<DeliveryAttemptClaim> claims;
    private final List<String> events;
    private int next;

    SequencedAttempts(List<DeliveryAttemptClaim> claims, List<String> events) {
      this.claims = claims;
      this.events = events;
    }

    public List<DeliveryAttemptClaim> claimDue(int batch, Duration lease) {
      assertThat(batch).isEqualTo(1);
      events.add("claim-" + next);
      return next < claims.size() ? List.of(claims.get(next++)) : List.of();
    }

    public void recordProviderAccepted(
        DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration duration) {
      events.add("complete-" + claims.indexOf(claim));
    }

    public void recordTransientRetry(
        DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration duration) {}

    public void recordAmbiguous(
        DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome, Duration duration) {}

    public void recordPermanentFailure(
        DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome) {}

    public void recordLocalPermanentFailure(DeliveryAttemptClaim claim) {}

    public void recordExpired(DeliveryAttemptClaim claim, ProviderDispatchOutcome outcome) {}
  }

  private static final class SequencedProvider implements NotificationProviderGateway {
    private final List<DeliveryAttemptClaim> claims;
    private final List<String> events;

    SequencedProvider(List<DeliveryAttemptClaim> claims, List<String> events) {
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
      int index =
          claims.stream()
              .map(DeliveryAttemptClaim::notificationId)
              .toList()
              .indexOf(message.notificationId());
      events.add("remote-" + index);
      return outcome(ProviderAttemptClassification.DEFINITIVE_ACCEPTED);
    }

    public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
      throw new UnsupportedOperationException();
    }
  }

  private record FakeProvider(
      NotificationChannel channel, ProviderDispatchOutcome outcome, boolean failDispatch)
      implements NotificationProviderGateway {
    public boolean liveDelivery() {
      return true;
    }

    public ProviderDispatchOutcome dispatch(ProviderDispatchMessage message) {
      if (failDispatch) throw new IllegalStateException("provider timeout");
      return outcome;
    }

    public ProviderReconciliationOutcome reconcile(ProviderReconciliationRequest request) {
      return ProviderReconciliationOutcome.live(ProviderReconciliationStatus.PENDING, null, "corr");
    }
  }

  private static final class FixedRandom implements RandomGenerator {
    public long nextLong() {
      return 0L;
    }

    public double nextDouble() {
      return 0.5d;
    }
  }
}
