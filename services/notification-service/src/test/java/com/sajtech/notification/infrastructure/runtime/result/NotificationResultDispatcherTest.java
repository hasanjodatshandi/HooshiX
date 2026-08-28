package com.sajtech.notification.infrastructure.runtime.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sajtech.notification.application.delivery.port.out.DeliveryDatabaseTimePort;
import com.sajtech.notification.application.result.model.NotificationResultOutboxRecord;
import com.sajtech.notification.application.result.port.out.NotificationResultCallbackPort;
import com.sajtech.notification.application.result.port.out.NotificationResultOutboxRepository;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotificationResultDispatcherTest {
  private static final Instant NOW = Instant.parse("2026-08-29T09:00:00Z");

  @Test
  void claimsEachResultOnlyAfterPriorCallbackAndCompletion() {
    NotificationResultOutboxRepository outbox = mock(NotificationResultOutboxRepository.class);
    NotificationResultCallbackPort callback = mock(NotificationResultCallbackPort.class);
    DeliveryDatabaseTimePort databaseTime = () -> NOW;
    List<String> events = new ArrayList<>();
    AtomicInteger claims = new AtomicInteger();
    List<NotificationResultOutboxRecord> records = List.of(record(), record());
    when(outbox.claimDue(eq(1), eq(Duration.ofSeconds(30))))
        .thenAnswer(
            ignored -> {
              int index = claims.getAndIncrement();
              events.add("claim-" + index);
              return index < records.size() ? List.of(records.get(index)) : List.of();
            });
    doAnswer(
            invocation -> {
              events.add("remote-" + records.indexOf(invocation.getArgument(0)));
              return null;
            })
        .when(callback)
        .report(any());
    doAnswer(
            invocation -> {
              events.add("complete-" + indexOf(records, invocation.getArgument(0)));
              return null;
            })
        .when(outbox)
        .markCompleted(any());

    boolean busy =
        new NotificationResultDispatcher(outbox, callback, databaseTime, new SimpleMeterRegistry())
            .dispatchDue();

    assertThat(busy).isTrue();
    assertThat(events)
        .containsExactly(
            "claim-0", "remote-0", "complete-0", "claim-1", "remote-1", "complete-1", "claim-2");
  }

  private static NotificationResultOutboxRecord record() {
    return new NotificationResultOutboxRecord(
        UUID.randomUUID(), UUID.randomUUID(), NotificationLifecycle.DELIVERED, NOW, 0);
  }

  private static int indexOf(List<NotificationResultOutboxRecord> records, UUID id) {
    for (int index = 0; index < records.size(); index++) {
      if (records.get(index).outboxId().equals(id)) return index;
    }
    return -1;
  }
}
