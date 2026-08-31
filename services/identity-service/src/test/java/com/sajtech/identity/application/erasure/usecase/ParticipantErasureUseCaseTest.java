package com.sajtech.identity.application.erasure.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.sajtech.identity.application.erasure.ErasureError;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureParticipant;
import com.sajtech.identity.application.erasure.model.ParticipantErasureTarget;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ParticipantErasureUseCaseTest {
  private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");

  @Test
  void matchingWorkloadBeginsItsParticipantPageInsideOneTransaction() {
    ErasureStore store = mock(ErasureStore.class);
    TrackingTransactions transactions = new TrackingTransactions();
    ParticipantErasureUseCase useCase =
        new ParticipantErasureUseCase(store, transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    UUID eventId = UUID.randomUUID();
    UUID requestId = UUID.randomUUID();
    ParticipantErasureTarget expected =
        new ParticipantErasureTarget(
            ErasureParticipant.NOTIFICATION_SERVICE,
            UUID.randomUUID(),
            List.of(UUID.randomUUID()),
            "next",
            false);
    when(store.beginParticipant(
            eventId,
            requestId,
            ErasureParticipant.NOTIFICATION_SERVICE,
            "notification-erasure-v1",
            "page-1",
            NOW))
        .thenReturn(expected);

    ParticipantErasureTarget result =
        useCase.begin(
            eventId,
            requestId,
            ErasureParticipant.NOTIFICATION_SERVICE,
            "notification-erasure-v1",
            "page-1",
            "notification-service");

    assertThat(result).isEqualTo(expected);
    assertThat(transactions.calls).isEqualTo(1);
  }

  @Test
  void mismatchedOrMissingWorkloadFailsClosedBeforePersistence() {
    ErasureStore store = mock(ErasureStore.class);
    TrackingTransactions transactions = new TrackingTransactions();
    ParticipantErasureUseCase useCase =
        new ParticipantErasureUseCase(store, transactions, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                useCase.begin(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ErasureParticipant.AUTHORIZATION_SERVICE,
                    "authorization-erasure-v1",
                    "",
                    "notification-service"))
        .isInstanceOfSatisfying(
            ErasureException.class,
            error -> assertThat(error.error()).isEqualTo(ErasureError.FORBIDDEN));
    assertThatThrownBy(
            () ->
                useCase.begin(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ErasureParticipant.AUTHORIZATION_SERVICE,
                    "authorization-erasure-v1",
                    "",
                    null))
        .isInstanceOfSatisfying(
            ErasureException.class,
            error -> assertThat(error.error()).isEqualTo(ErasureError.INVALID_ARGUMENT));
    verifyNoInteractions(store);
    assertThat(transactions.calls).isZero();
  }

  private static final class TrackingTransactions implements TransactionRunner {
    private int calls;

    @Override
    public <T> T required(Supplier<T> work) {
      calls++;
      return work.get();
    }
  }
}
