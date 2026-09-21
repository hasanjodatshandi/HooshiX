package com.sajtech.conversation.infrastructure.model;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.port.out.ModelPolicyProvider;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public final class GitGovernedModelPolicyProvider implements ModelPolicyProvider {
  static final String GOVERNANCE_RESOURCE = "mlops/governance/v1/governance.json";
  static final String PROMPT_RESOURCE = "mlops/prompts/conversation-system-v1.txt";
  private static final String EXPECTED_DECISION_STATUS = "APPROVED_RUNTIME_ENABLED";
  private static final String EXPECTED_DATA_CONTROL = "APPROVED";
  private final boolean runtimeEnabled;
  private final int canaryPercent;
  private final ModelExecutionPolicy approvedPolicy;

  public GitGovernedModelPolicyProvider(boolean runtimeEnabled) {
    this(runtimeEnabled, runtimeEnabled ? 100 : 0);
  }

  public GitGovernedModelPolicyProvider(boolean runtimeEnabled, int canaryPercent) {
    this(runtimeEnabled, canaryPercent, loadJson(GOVERNANCE_RESOURCE), loadBytes(PROMPT_RESOURCE));
  }

  GitGovernedModelPolicyProvider(
      boolean runtimeEnabled, int canaryPercent, JsonNode governance, byte[] prompt) {
    this.runtimeEnabled = runtimeEnabled;
    if ((runtimeEnabled && !java.util.Set.of(1, 5, 25, 100).contains(canaryPercent))
        || (!runtimeEnabled && canaryPercent != 0)) {
      throw new IllegalArgumentException("Model canary configuration is invalid");
    }
    this.canaryPercent = canaryPercent;
    String lifecycle = canaryPercent == 100 ? "APPROVED_100" : "CANARY_" + canaryPercent;
    this.approvedPolicy = approvedPolicy(governance, prompt, lifecycle);
  }

  GitGovernedModelPolicyProvider(boolean runtimeEnabled, JsonNode governance, byte[] prompt) {
    this(runtimeEnabled, runtimeEnabled ? 100 : 0, governance, prompt);
  }

  @Override
  public ModelExecutionPolicy requireApprovedPolicy() {
    if (!runtimeEnabled || approvedPolicy == null) {
      throw new ConversationException(
          ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled");
    }
    return approvedPolicy;
  }

  @Override
  public ModelExecutionPolicy requireApprovedPolicy(UUID tenantId) {
    Objects.requireNonNull(tenantId);
    ModelExecutionPolicy policy = requireApprovedPolicy();
    byte[] digest;
    try {
      digest = MessageDigest.getInstance("SHA-256").digest(uuidBytes(tenantId));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
    int cohort = Math.floorMod(java.nio.ByteBuffer.wrap(digest, 0, 4).getInt(), 100);
    if (cohort >= canaryPercent) {
      throw new ConversationException(
          ConversationError.MODEL_EXECUTION_DISABLED, "Model execution is disabled");
    }
    return policy;
  }

  private static byte[] uuidBytes(UUID value) {
    return java.nio.ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  private static ModelExecutionPolicy approvedPolicy(
      JsonNode root, byte[] prompt, String expectedLifecycle) {
    try {
      if (!root.path("schema_version").isInt()
          || root.path("schema_version").intValue() != 1
          || !EXPECTED_DECISION_STATUS.equals(text(root, "decision_status"))
          || !EXPECTED_DATA_CONTROL.equals(
              text(root.path("provider_data_controls"), "approval_status"))) {
        return null;
      }
      JsonNode models = root.path("model_catalog");
      JsonNode prices = root.path("price_catalog");
      if (!models.isArray() || models.size() != 1 || !prices.isArray()) return null;
      JsonNode model = models.get(0);
      if (!bool(model, "execution_enabled")
          || !expectedLifecycle.equals(text(model, "lifecycle"))
          || !"openai".equals(text(model, "provider"))
          || !"responses".equals(text(model, "endpoint"))
          || bool(model, "store")
          || bool(model, "background")
          || bool(model, "tools_enabled")) {
        return null;
      }
      if (!"none".equals(text(model, "reasoning_effort"))) return null;
      String promptId = text(model, "prompt_id");
      String promptVersion = text(model, "prompt_version");
      if (!promptMatches(
          root.path("prompt_catalog"), promptId, promptVersion, prompt, expectedLifecycle)) {
        return null;
      }
      String priceId = text(model, "price_id");
      String priceVersion = text(model, "price_version");
      JsonNode price = null;
      for (JsonNode candidate : prices) {
        if (priceId.equals(text(candidate, "price_id"))
            && priceVersion.equals(text(candidate, "price_version"))) {
          price = candidate;
        }
      }
      if (price == null) return null;
      if (!"USD".equals(text(price, "currency"))
          || !"MICRO_USD_PER_MILLION_TOKENS".equals(text(price, "unit"))) return null;
      JsonNode reservationNode = price.path("maximum_request_reservation_micro_usd");
      if (!reservationNode.isIntegralNumber()) return null;
      long reservation = reservationNode.longValue();
      return new ModelExecutionPolicy(
          text(model, "logical_id"),
          text(model, "provider_model_id"),
          promptVersion,
          priceVersion,
          positiveInt(model, "max_input_tokens"),
          positiveInt(model, "max_output_tokens"),
          nonNegativeLong(price, "input", false),
          nonNegativeLong(price, "cached_input", true),
          nonNegativeLong(price, "output", false),
          reservation);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private static boolean promptMatches(
      JsonNode prompts,
      String promptId,
      String promptVersion,
      byte[] prompt,
      String expectedLifecycle) {
    if (!prompts.isArray() || prompt == null || prompt.length == 0) return false;
    String digest = sha256(prompt);
    for (JsonNode candidate : prompts) {
      if (promptId.equals(text(candidate, "prompt_id"))
          && promptVersion.equals(text(candidate, "prompt_version"))
          && expectedLifecycle.equals(text(candidate, "status"))
          && PROMPT_RESOURCE.equals(text(candidate, "path"))
          && digest.equals(text(candidate, "sha256"))) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode loadJson(String resource) {
    try {
      return new ObjectMapper().readTree(loadBytes(resource));
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Model governance resource is invalid", exception);
    }
  }

  private static byte[] loadBytes(String resource) {
    try (InputStream input =
        GitGovernedModelPolicyProvider.class.getClassLoader().getResourceAsStream(resource)) {
      if (input == null) throw new IOException("missing resource");
      return input.readAllBytes();
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Model governance resource is invalid", exception);
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.textValue();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.booleanValue();
  }

  private static int positiveInt(JsonNode node, String field) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null
        || !value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() <= 0) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return value.intValue();
  }

  private static long nonNegativeLong(JsonNode node, String field, boolean zeroAllowed) {
    JsonNode value = Objects.requireNonNull(node).get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    long result = value.longValue();
    if (result < 0 || (!zeroAllowed && result == 0)) {
      throw new IllegalArgumentException("Model governance field is invalid");
    }
    return result;
  }
}
