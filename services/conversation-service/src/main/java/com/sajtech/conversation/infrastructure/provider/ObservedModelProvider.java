package com.sajtech.conversation.infrastructure.provider;

import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Objects;
import java.util.UUID;

public final class ObservedModelProvider implements ModelProvider {
  private final ModelProvider delegate;
  private final MeterRegistry meters;

  public ObservedModelProvider(ModelProvider delegate, MeterRegistry meters) {
    this.delegate = Objects.requireNonNull(delegate);
    this.meters = Objects.requireNonNull(meters);
  }

  @Override
  public ModelProviderResult execute(ModelProviderRequest request) {
    Timer.Sample sample = Timer.start(meters);
    ModelProviderResult result = delegate.execute(request);
    meters
        .counter("hooshix.conversation.provider.calls", "outcome", result.outcome().name())
        .increment();
    sample.stop(
        meters.timer("hooshix.conversation.provider.duration", "outcome", result.outcome().name()));
    return result;
  }

  @Override
  public boolean cancel(UUID runId) {
    boolean signaled = delegate.cancel(runId);
    meters
        .counter(
            "hooshix.conversation.provider.cancellations",
            "result",
            signaled ? "signaled" : "not_active")
        .increment();
    return signaled;
  }
}
