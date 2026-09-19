package com.sajtech.conversation.infrastructure.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderMessage;
import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.domain.MessageRole;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileBackedOpenAiModelProviderTest {
  @TempDir Path directory;

  @Test
  void missingCredentialFailsBeforeTransportAsDefinitiveUnavailable() {
    var provider = new FileBackedOpenAiModelProvider(directory.resolve("missing"));

    assertThat(provider.isConfigured()).isFalse();
    assertThat(provider.execute(request()).outcome())
        .isEqualTo(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE);
  }

  private static ModelProviderRequest request() {
    var policy =
        new ModelExecutionPolicy(
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
    return new ModelProviderRequest(
        policy, List.of(new ModelProviderMessage(MessageRole.USER, "question")));
  }
}
