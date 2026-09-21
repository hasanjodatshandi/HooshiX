package com.sajtech.conversation.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sajtech.conversation.application.model.*;
import com.sajtech.conversation.application.port.out.ModelProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObservedModelProviderTest {
  @Test
  void recordsBoundedOutcomeDurationAndCancellationResults() {
    ModelProvider delegate = mock(ModelProvider.class);
    var meters = new SimpleMeterRegistry();
    var observed = new ObservedModelProvider(delegate, meters);
    UUID runId = UUID.randomUUID();
    var request =
        new ModelProviderRequest(
            runId,
            new ModelExecutionPolicy(
                "conversation-primary",
                "provider-model",
                "1.0.0",
                "2026-09-12",
                10,
                10,
                1,
                0,
                1,
                2),
            List.of(
                new ModelProviderMessage(
                    com.sajtech.conversation.domain.MessageRole.USER, "question")));
    when(delegate.execute(request))
        .thenReturn(ModelProviderResult.failure(ModelProviderOutcome.SAFETY_REJECTED));
    when(delegate.cancel(runId)).thenReturn(true, false);

    assertThat(observed.execute(request).outcome()).isEqualTo(ModelProviderOutcome.SAFETY_REJECTED);
    assertThat(observed.cancel(runId)).isTrue();
    assertThat(observed.cancel(runId)).isFalse();

    assertThat(
            meters
                .counter("hooshix.conversation.provider.calls", "outcome", "SAFETY_REJECTED")
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .timer("hooshix.conversation.provider.duration", "outcome", "SAFETY_REJECTED")
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .counter("hooshix.conversation.provider.cancellations", "result", "signaled")
                .count())
        .isEqualTo(1);
    assertThat(
            meters
                .counter("hooshix.conversation.provider.cancellations", "result", "not_active")
                .count())
        .isEqualTo(1);
  }
}
