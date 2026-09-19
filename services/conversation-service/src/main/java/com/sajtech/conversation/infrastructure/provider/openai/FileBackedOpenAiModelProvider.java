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

public final class FileBackedOpenAiModelProvider implements ModelProvider {
  private static final String PROMPT_RESOURCE = "mlops/prompts/conversation-system-v1.txt";
  private final Path apiKeyPath;
  private final String systemPrompt;

  public FileBackedOpenAiModelProvider(Path apiKeyPath) {
    this.apiKeyPath = Objects.requireNonNull(apiKeyPath);
    this.systemPrompt = loadPrompt();
  }

  @Override
  public ModelProviderResult execute(ModelProviderRequest request) {
    Objects.requireNonNull(request);
    try {
      return new OpenAiResponsesAdapter(loadApiKey(), systemPrompt).execute(request);
    } catch (RuntimeException exception) {
      return ModelProviderResult.failure(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE);
    }
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
