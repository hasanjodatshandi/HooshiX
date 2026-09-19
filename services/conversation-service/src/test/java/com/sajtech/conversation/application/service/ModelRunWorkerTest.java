package com.sajtech.conversation.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ClaimedModelRun;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderMessage;
import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import com.sajtech.conversation.application.port.out.ModelProvider;
import com.sajtech.conversation.application.port.out.ModelRunWorkerRepository;
import com.sajtech.conversation.domain.MessageRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelRunWorkerTest {
  private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
  private final ModelPolicyProvider policies = mock(ModelPolicyProvider.class);
  private final ModelRunWorkerRepository runs = mock(ModelRunWorkerRepository.class);
  private final ModelProvider provider = mock(ModelProvider.class);

  @Test
  void claimProviderAndCompletionAreSeparateOrderedPortCalls() {
    ModelExecutionPolicy policy = policy();
    ClaimedModelRun claim = claim();
    ModelProviderResult result =
        new ModelProviderResult(ModelProviderOutcome.SUCCEEDED, "answer", 10, 0, 5);
    when(policies.requireApprovedPolicy()).thenReturn(policy);
    when(runs.claim(policy, NOW, Duration.ofSeconds(65), 1)).thenReturn(Optional.of(claim));
    when(provider.execute(any())).thenReturn(result);

    assertThat(worker(3).runOnce()).isTrue();

    var order = inOrder(runs, provider);
    order.verify(runs).expireUnknown(policy, NOW, 20);
    order.verify(runs).claim(policy, NOW, Duration.ofSeconds(65), 1);
    order.verify(provider).execute(any());
    order.verify(runs).complete(claim, policy, result, NOW);
  }

  @Test
  void disabledGovernanceNeverTouchesQueueOrProvider() {
    when(policies.requireApprovedPolicy())
        .thenThrow(
            new ConversationException(
                ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled"));

    assertThat(worker(3).runOnce()).isFalse();

    verifyNoInteractions(runs, provider);
  }

  @Test
  void circuitSuppressesNewClaimsAfterBoundedProviderFailures() {
    ModelExecutionPolicy policy = policy();
    ClaimedModelRun claim = claim();
    when(policies.requireApprovedPolicy()).thenReturn(policy);
    when(runs.claim(policy, NOW, Duration.ofSeconds(65), 1)).thenReturn(Optional.of(claim));
    when(provider.execute(any()))
        .thenReturn(ModelProviderResult.failure(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE));
    ModelRunWorker worker = worker(2);

    assertThat(worker.runOnce()).isTrue();
    assertThat(worker.runOnce()).isTrue();
    assertThat(worker.runOnce()).isFalse();

    verify(runs, times(2)).claim(policy, NOW, Duration.ofSeconds(65), 1);
    verify(provider, times(2)).execute(any());
  }

  private ModelRunWorker worker(int breakerThreshold) {
    return new ModelRunWorker(
        policies,
        runs,
        provider,
        Clock.fixed(NOW, ZoneOffset.UTC),
        Duration.ofSeconds(65),
        1,
        20,
        breakerThreshold,
        Duration.ofSeconds(30));
  }

  private static ClaimedModelRun claim() {
    return new ClaimedModelRun(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(new ModelProviderMessage(MessageRole.USER, "question")));
  }

  private static ModelExecutionPolicy policy() {
    return new ModelExecutionPolicy(
        "conversation-primary",
        "gpt-5.4-2026-03-05",
        "1.0.0",
        "2026-09-12",
        16_000,
        2_000,
        2_500_000,
        250_000,
        15_000_000,
        70_000);
  }
}
