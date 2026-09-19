package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ClaimedModelRun;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface ModelRunWorkerRepository {
  Optional<ClaimedModelRun> claim(
      ModelExecutionPolicy policy,
      Instant now,
      Duration leaseDuration,
      int maximumConcurrentPerTenant);

  void complete(
      ClaimedModelRun claim, ModelExecutionPolicy policy, ModelProviderResult result, Instant now);

  int expireUnknown(ModelExecutionPolicy policy, Instant now, int maximumBatchSize);
}
