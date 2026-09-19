package com.sajtech.conversation.infrastructure.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderMessage;
import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.domain.MessageRole;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiResponsesAdapterTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void requestPinsDestinationModelAndStatelessNoToolOptions() throws Exception {
    var adapter = new OpenAiResponsesAdapter("fixture-key", "fixed system prompt");
    var providerRequest =
        new ModelProviderRequest(
            policy(),
            List.of(
                new ModelProviderMessage(MessageRole.USER, "hello"),
                new ModelProviderMessage(MessageRole.ASSISTANT, "hi")));

    var request = adapter.buildRequest(providerRequest);
    JsonNode body = JSON.readTree(adapter.requestBody(providerRequest));

    assertThat(request.uri()).isEqualTo(OpenAiResponsesAdapter.RESPONSES_URI);
    assertThat(request.timeout()).contains(OpenAiResponsesAdapter.REQUEST_TIMEOUT);
    assertThat(body.path("model").textValue()).isEqualTo("gpt-5.4-2026-03-05");
    assertThat(body.path("instructions").textValue()).isEqualTo("fixed system prompt");
    assertThat(body.path("store").booleanValue()).isFalse();
    assertThat(body.path("background").booleanValue()).isFalse();
    assertThat(body.path("tools")).isEmpty();
    assertThat(body.path("reasoning").path("effort").textValue()).isEqualTo("none");
    assertThat(body.path("max_output_tokens").intValue()).isEqualTo(2_000);
    assertThat(body.path("input")).hasSize(2);
  }

  @Test
  void completedFixtureReturnsOnlyTextAndBoundedUsage() {
    String fixture =
        """
        {
          "status":"completed",
          "output":[
            {"type":"reasoning","summary":[]},
            {"type":"message","role":"assistant","content":[
              {"type":"output_text","text":"safe answer","annotations":[]}
            ]}
          ],
          "usage":{"input_tokens":120,"input_tokens_details":{"cached_tokens":20},"output_tokens":30}
        }
        """;

    var result = OpenAiResponsesAdapter.classify(200, fixture);

    assertThat(result.outcome()).isEqualTo(ModelProviderOutcome.SUCCEEDED);
    assertThat(result.output()).isEqualTo("safe answer");
    assertThat(result.inputTokens()).isEqualTo(120);
    assertThat(result.cachedInputTokens()).isEqualTo(20);
    assertThat(result.outputTokens()).isEqualTo(30);
  }

  @Test
  void failuresPreserveCertaintyWithoutLeakingProviderBody() {
    assertThat(OpenAiResponsesAdapter.classify(400, "sensitive").outcome())
        .isEqualTo(ModelProviderOutcome.DEFINITIVE_REJECTION);
    assertThat(OpenAiResponsesAdapter.classify(429, "sensitive").outcome())
        .isEqualTo(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE);
    assertThat(OpenAiResponsesAdapter.classify(503, "sensitive").outcome())
        .isEqualTo(ModelProviderOutcome.AMBIGUOUS);
    assertThat(OpenAiResponsesAdapter.classify(200, "{}").outcome())
        .isEqualTo(ModelProviderOutcome.INVALID_RESPONSE);
    assertThat(
            OpenAiResponsesAdapter.classify(
                    200,
                    "{\"status\":\"incomplete\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .outcome())
        .isEqualTo(ModelProviderOutcome.INVALID_RESPONSE);
    assertThat(
            OpenAiResponsesAdapter.classify(
                    200,
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .cachedInputTokens())
        .isZero();
    assertThat(
            OpenAiResponsesAdapter.classify(
                    200,
                    "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}],\"usage\":{\"input_tokens\":1,\"input_tokens_details\":{\"cached_tokens\":2},\"output_tokens\":1}}")
                .outcome())
        .isEqualTo(ModelProviderOutcome.INVALID_RESPONSE);
  }

  private static ModelExecutionPolicy policy() {
    return new ModelExecutionPolicy(
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
  }
}
