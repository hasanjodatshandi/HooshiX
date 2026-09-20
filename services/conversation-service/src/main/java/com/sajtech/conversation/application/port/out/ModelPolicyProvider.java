package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import java.util.UUID;

public interface ModelPolicyProvider {
  ModelExecutionPolicy requireApprovedPolicy();

  ModelExecutionPolicy requireApprovedPolicy(UUID tenantId);
}
