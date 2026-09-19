package com.sajtech.conversation.application.port.out;

import com.sajtech.conversation.application.model.ModelExecutionPolicy;

public interface ModelPolicyProvider {
  ModelExecutionPolicy requireApprovedPolicy();
}
