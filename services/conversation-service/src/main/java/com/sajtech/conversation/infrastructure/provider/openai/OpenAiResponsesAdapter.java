package com.sajtech.conversation.infrastructure.provider.openai;

import com.sajtech.conversation.application.model.ModelProviderMessage;
import com.sajtech.conversation.application.model.ModelProviderOutcome;
import com.sajtech.conversation.application.model.ModelProviderRequest;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelProvider;
import com.sajtech.conversation.domain.MessageRole;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLParameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class OpenAiResponsesAdapter implements ModelProvider {
  static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
  private static final int MAXIMUM_RESPONSE_BYTES = 512 * 1024;
  private static final int MAXIMUM_OUTPUT_CHARACTERS = 65_536;
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient SHARED_CLIENT = productionClient();
  private final String apiKey;
  private final String systemPrompt;
  private final HttpClient client;

  public OpenAiResponsesAdapter(String apiKey, String systemPrompt) {
    this(apiKey, systemPrompt, SHARED_CLIENT);
  }

  OpenAiResponsesAdapter(String apiKey, String systemPrompt, HttpClient client) {
    this.apiKey = requireSecret(apiKey);
    this.systemPrompt = requireSystemPrompt(systemPrompt);
    this.client = Objects.requireNonNull(client);
  }

  @Override
  public ModelProviderResult execute(ModelProviderRequest request) {
    Objects.requireNonNull(request);
    try {
      HttpResponse<InputStream> response =
          client.send(buildRequest(request), HttpResponse.BodyHandlers.ofInputStream());
      return classify(response.statusCode(), boundedBody(response));
    } catch (HttpTimeoutException exception) {
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    } catch (IOException | RuntimeException exception) {
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    }
  }

  HttpRequest buildRequest(ModelProviderRequest request) {
    return HttpRequest.newBuilder(RESPONSES_URI)
        .timeout(REQUEST_TIMEOUT)
        .header("Authorization", "Bearer " + apiKey)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
        .build();
  }

  String requestBody(ModelProviderRequest request) {
    List<Map<String, String>> input = new ArrayList<>(request.messages().size());
    for (ModelProviderMessage message : request.messages()) {
      input.add(
          Map.of(
              "role",
              message.role() == MessageRole.USER ? "user" : "assistant",
              "content",
              message.content()));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", request.policy().providerModelId());
    body.put("instructions", systemPrompt);
    body.put("input", input);
    body.put("max_output_tokens", request.policy().maximumOutputTokens());
    body.put("reasoning", Map.of("effort", "none"));
    body.put("store", false);
    body.put("background", false);
    body.put("tools", List.of());
    try {
      return JSON.writeValueAsString(body);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Unable to encode the fixed provider request", exception);
    }
  }

  static ModelProviderResult classify(int statusCode, String body) {
    if (statusCode == 429) {
      return ModelProviderResult.failure(ModelProviderOutcome.DEFINITIVE_UNAVAILABLE);
    }
    if (statusCode >= 500) {
      return ModelProviderResult.failure(ModelProviderOutcome.AMBIGUOUS);
    }
    if (statusCode >= 400) {
      return ModelProviderResult.failure(ModelProviderOutcome.DEFINITIVE_REJECTION);
    }
    if (statusCode != 200) {
      return ModelProviderResult.failure(ModelProviderOutcome.INVALID_RESPONSE);
    }
    try {
      JsonNode root = JSON.readTree(body);
      if (!"completed".equals(requiredText(root, "status"))) {
        return ModelProviderResult.failure(ModelProviderOutcome.INVALID_RESPONSE);
      }
      String output = extractOutput(root.path("output"));
      JsonNode usage = root.path("usage");
      int inputTokens = requiredNonNegativeInt(usage, "input_tokens");
      int outputTokens = requiredNonNegativeInt(usage, "output_tokens");
      JsonNode details = usage.path("input_tokens_details");
      int cachedTokens =
          details.has("cached_tokens") ? requiredNonNegativeInt(details, "cached_tokens") : 0;
      if (cachedTokens > inputTokens) {
        return ModelProviderResult.failure(ModelProviderOutcome.INVALID_RESPONSE);
      }
      return new ModelProviderResult(
          ModelProviderOutcome.SUCCEEDED, output, inputTokens, cachedTokens, outputTokens);
    } catch (RuntimeException exception) {
      return ModelProviderResult.failure(ModelProviderOutcome.INVALID_RESPONSE);
    }
  }

  private static String extractOutput(JsonNode outputItems) {
    if (!outputItems.isArray()) throw new IllegalArgumentException("Provider output is invalid");
    StringBuilder output = new StringBuilder();
    for (JsonNode item : outputItems) {
      if (!"message".equals(optionalText(item, "type"))
          || !"assistant".equals(optionalText(item, "role"))) continue;
      JsonNode content = item.path("content");
      if (!content.isArray()) throw new IllegalArgumentException("Provider output is invalid");
      for (JsonNode part : content) {
        if (!"output_text".equals(optionalText(part, "type"))) continue;
        String text = requiredText(part, "text");
        if (!output.isEmpty()) output.append('\n');
        output.append(text);
        if (output.length() > MAXIMUM_OUTPUT_CHARACTERS) {
          throw new IllegalArgumentException("Provider output exceeds the configured bound");
        }
      }
    }
    String result = output.toString();
    if (result.isBlank()) throw new IllegalArgumentException("Provider output is empty");
    return result;
  }

  private static String requiredText(JsonNode node, String field) {
    String result = optionalText(node, field);
    if (result == null || result.isBlank()) {
      throw new IllegalArgumentException("Provider response field is invalid");
    }
    return result;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  private static int requiredNonNegativeInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < 0) {
      throw new IllegalArgumentException("Provider usage is invalid");
    }
    return value.intValue();
  }

  private static String boundedBody(HttpResponse<InputStream> response) throws IOException {
    try (InputStream input = response.body()) {
      byte[] bytes = input.readNBytes(MAXIMUM_RESPONSE_BYTES + 1);
      if (bytes.length > MAXIMUM_RESPONSE_BYTES) {
        throw new IOException("Provider response exceeded the configured bound");
      }
      return new String(bytes, StandardCharsets.UTF_8);
    }
  }

  private static HttpClient productionClient() {
    SSLParameters tls = new SSLParameters();
    tls.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER)
        .sslParameters(tls)
        .build();
  }

  private static String requireSecret(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 512
        || value.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Provider credential is invalid");
    }
    return value;
  }

  private static String requireSystemPrompt(String value) {
    if (value == null
        || value.isBlank()
        || value.length() > 65_536
        || value.codePoints().anyMatch(codePoint -> codePoint == 0)) {
      throw new IllegalArgumentException("Provider system prompt is invalid");
    }
    return value;
  }
}
