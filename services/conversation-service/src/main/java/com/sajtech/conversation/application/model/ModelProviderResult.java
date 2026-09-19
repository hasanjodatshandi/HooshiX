package com.sajtech.conversation.application.model;

public record ModelProviderResult(
    ModelProviderOutcome outcome,
    String output,
    Integer inputTokens,
    Integer cachedInputTokens,
    Integer outputTokens) {
  public ModelProviderResult {
    if (outcome == null) throw new IllegalArgumentException("Provider outcome is required");
    boolean success = outcome == ModelProviderOutcome.SUCCEEDED;
    boolean completeUsage =
        inputTokens != null && cachedInputTokens != null && outputTokens != null;
    if (success != (output != null)
        || success != completeUsage
        || (output != null && (output.isBlank() || output.length() > 65_536))
        || (completeUsage
            && (inputTokens < 0
                || cachedInputTokens < 0
                || cachedInputTokens > inputTokens
                || outputTokens < 0))) {
      throw new IllegalArgumentException("Provider result is invalid");
    }
  }

  public static ModelProviderResult failure(ModelProviderOutcome outcome) {
    if (outcome == ModelProviderOutcome.SUCCEEDED) {
      throw new IllegalArgumentException("Successful provider result requires output and usage");
    }
    return new ModelProviderResult(outcome, null, null, null, null);
  }
}
