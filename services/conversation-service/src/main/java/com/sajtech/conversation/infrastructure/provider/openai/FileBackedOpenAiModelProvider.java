package com.sajtech.conversation.infrastructure.provider.openai;

import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FileBackedOpenAiModelProvider implements ModelProvider {
  private static final String PROMPT_RESOURCE = "mlops/prompts/conversation-system-v1.txt";
  private final Path apiKeyPath;
  private final String systemPrompt;
  private final ConcurrentMap<UUID, OpenAiResponsesAdapter> activeCalls = new ConcurrentHashMap<>();
  private final java.util.Set<UUID> cancellationRequested = ConcurrentHashMap.newKeySet();

  public FileBackedOpenAiModelProvider(Path apiKeyPath) {
    this.apiKeyPath = Objects.requireNonNull(apiKeyPath);
    this.systemPrompt = loadPrompt();
  }

  @Override
  public ModelProviderResult execute(ModelProviderRequest request) {
    Objects.requireNonNull(request);
    if (cancellationRequested.remove(request.runId())) {
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    }
    OpenAiResponsesAdapter adapter;
    try {
      adapter = new OpenAiResponsesAdapter(loadApiKey(), systemPrompt);
    } catch (RuntimeException exception) {
      return ModelProviderResult.failure(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE);
    }
    if (activeCalls.putIfAbsent(request.runId(), adapter) != null) {
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    }
    try {
      if (cancellationRequested.remove(request.runId())) adapter.cancel(request.runId());
      return adapter.execute(request);
    } finally {
      activeCalls.remove(request.runId(), adapter);
      cancellationRequested.remove(request.runId());
    }
  }

  @Override
  public boolean cancel(UUID runId) {
    Objects.requireNonNull(runId);
    cancellationRequested.add(runId);
    OpenAiResponsesAdapter adapter = activeCalls.get(runId);
    return adapter != null && adapter.cancel(runId);
  }

  public boolean isConfigured() {
    try {
      loadApiKey();
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private String loadApiKey() {
    if (!Files.isRegularFile(apiKeyPath)) {
      throw new IllegalStateException("Provider credential file is unavailable");
    }
    try {
      long size = Files.size(apiKeyPath);
      if (size < 1 || size > 1_024) {
        throw new IllegalStateException("Provider credential file is invalid");
      }
      return Files.readString(apiKeyPath, StandardCharsets.UTF_8).strip();
    } catch (IOException exception) {
      throw new IllegalStateException("Provider credential file is unavailable", exception);
    }
  }

  private static String loadPrompt() {
    try (InputStream input =
        FileBackedOpenAiModelProvider.class.getClassLoader().getResourceAsStream(PROMPT_RESOURCE)) {
      if (input == null) throw new IOException("missing prompt");
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Approved provider prompt is unavailable", exception);
    }
  }
}
