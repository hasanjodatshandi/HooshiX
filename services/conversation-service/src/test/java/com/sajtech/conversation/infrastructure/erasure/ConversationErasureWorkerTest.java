package com.sajtech.conversation.infrastructure.erasure;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationErasureWorkerTest {
  private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

  @Test
  void doesNothingWhenNoInboxItemIsDue() {
    var repository = mock(JdbcConversationErasureRepository.class);
    when(repository.claim(any(), any())).thenReturn(Optional.empty());

    worker(mock(IdentityErasureTargetClient.class), repository).run();

    verify(repository).claim(any(), any());
    verifyNoMoreInteractions(repository);
  }

  @Test
  void resolvesErasesAndCompletesOneClaim() {
    var repository = mock(JdbcConversationErasureRepository.class);
    var identity = mock(IdentityErasureTargetClient.class);
    var item = item(0);
    UUID userId = UUID.randomUUID();
    when(repository.claim(any(), any())).thenReturn(Optional.of(item));
    when(identity.resolve(item.eventId(), item.erasureRequestId(), item.participantPolicyVersion()))
        .thenReturn(userId);

    worker(identity, repository).run();

    verify(repository).eraseSubject(userId, NOW);
    verify(repository).complete(item, NOW);
  }

  @Test
  void retriesSafeFailureAndExhaustsAtFiniteLimit() {
    var repository = mock(JdbcConversationErasureRepository.class);
    var identity = mock(IdentityErasureTargetClient.class);
    var retry = item(0);
    when(repository.claim(any(), any())).thenReturn(Optional.of(retry));
    when(identity.resolve(any(), any(), anyString())).thenThrow(new IllegalStateException("safe"));

    worker(identity, repository).run();
    verify(repository)
        .reschedule(eq(retry.eventId()), eq(1), any(), eq("ILLEGALSTATEEXCEPTION"), eq(false));

    reset(repository, identity);
    var exhausted = item(47);
    when(repository.claim(any(), any())).thenReturn(Optional.of(exhausted));
    when(identity.resolve(any(), any(), anyString())).thenThrow(new IllegalStateException("safe"));

    worker(identity, repository).run();
    verify(repository)
        .reschedule(eq(exhausted.eventId()), eq(48), any(), eq("ILLEGALSTATEEXCEPTION"), eq(true));
  }

  private static ConversationErasureWorker worker(
      IdentityErasureTargetClient identity, JdbcConversationErasureRepository repository) {
    return new ConversationErasureWorker(
        identity, repository, Clock.fixed(NOW, ZoneOffset.UTC), new SimpleMeterRegistry());
  }

  private static JdbcConversationErasureRepository.InboxItem item(int attempts) {
    return new JdbcConversationErasureRepository.InboxItem(
        UUID.randomUUID(), UUID.randomUUID(), "2", attempts);
  }
}
