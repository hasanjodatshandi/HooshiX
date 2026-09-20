package com.sajtech.conversation.infrastructure.provider;

import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ObservedModelProvider implements ModelProvider {
  private final ModelProvider delegate;
  private final Map<com.sajtech.conversation.application.model.ModelProviderOutcome, Counter> calls;
  private final Map<com.sajtech.conversation.application.model.ModelProviderOutcome, Timer>
      durations;
  private final Counter cancellationSignaled;
  private final Counter cancellationNotActive;

  public ObservedModelProvider(ModelProvider delegate, MeterRegistry meters) {
    this.delegate = Objects.requireNonNull(delegate);
    Objects.requireNonNull(meters);
    var callMeters =
        new EnumMap<com.sajtech.conversation.application.model.ModelProviderOutcome, Counter>(
            com.sajtech.conversation.application.model.ModelProviderOutcome.class);
    var durationMeters =
        new EnumMap<com.sajtech.conversation.application.model.ModelProviderOutcome, Timer>(
            com.sajtech.conversation.application.model.ModelProviderOutcome.class);
    for (var outcome : com.sajtech.conversation.application.model.ModelProviderOutcome.values()) {
      callMeters.put(
          outcome,
          meters.counter("hooshix.conversation.provider.calls", "outcome", outcome.name()));
      durationMeters.put(
          outcome,
          meters.timer("hooshix.conversation.provider.duration", "outcome", outcome.name()));
    }
    calls = Map.copyOf(callMeters);
    durations = Map.copyOf(durationMeters);
    cancellationSignaled =
        meters.counter("hooshix.conversation.provider.cancellations", "result", "signaled");
    cancellationNotActive =
        meters.counter("hooshix.conversation.provider.cancellations", "result", "not_active");
  }

  @Override
  public ModelProviderResult execute(ModelProviderRequest request) {
    long started = System.nanoTime();
    ModelProviderResult result = delegate.execute(request);
    calls.get(result.outcome()).increment();
    durations.get(result.outcome()).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    return result;
  }

  @Override
  public boolean cancel(UUID runId) {
    boolean signaled = delegate.cancel(runId);
    (signaled ? cancellationSignaled : cancellationNotActive).increment();
    return signaled;
  }
}
