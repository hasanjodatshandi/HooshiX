package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import java.util.UUID;

public interface ModelProvider {
  ModelProviderResult execute(ModelProviderRequest request);

  boolean cancel(UUID runId);
}
