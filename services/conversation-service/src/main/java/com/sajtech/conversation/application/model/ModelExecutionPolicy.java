package com.sajtech.conversation.application.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelExecutionPolicy(
    String modelAlias,
    String providerModelId,
    String promptVersion,
    String priceVersion,
    int maximumInputTokens,
    int maximumOutputTokens,
    long inputPriceMicroUsdPerMillionTokens,
    long cachedInputPriceMicroUsdPerMillionTokens,
    long outputPriceMicroUsdPerMillionTokens,
    long maximumReservationMicroUsd) {
  private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
  private static final Pattern PROVIDER_MODEL = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,127}$");
  private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
  private static final Pattern PRICE_VERSION = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

  public ModelExecutionPolicy {
    Objects.requireNonNull(modelAlias);
    Objects.requireNonNull(providerModelId);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(priceVersion);
    if (!ALIAS.matcher(modelAlias).matches()
        || !PROVIDER_MODEL.matcher(providerModelId).matches()
        || !SEMVER.matcher(promptVersion).matches()
        || !PRICE_VERSION.matcher(priceVersion).matches()
        || maximumInputTokens <= 0
        || maximumOutputTokens <= 0
        || inputPriceMicroUsdPerMillionTokens <= 0
        || cachedInputPriceMicroUsdPerMillionTokens < 0
        || outputPriceMicroUsdPerMillionTokens <= 0
        || maximumReservationMicroUsd <= 0) {
      throw new IllegalArgumentException("Model execution policy is invalid");
    }
    long expectedReservation =
        Math.addExact(
            divideRoundUp(
                Math.multiplyExact((long) maximumInputTokens, inputPriceMicroUsdPerMillionTokens),
                1_000_000L),
            divideRoundUp(
                Math.multiplyExact((long) maximumOutputTokens, outputPriceMicroUsdPerMillionTokens),
                1_000_000L));
    if (maximumReservationMicroUsd != expectedReservation) {
      throw new IllegalArgumentException("Model execution policy reservation is inconsistent");
    }
  }

  public long actualCostMicroUsd(int inputTokens, int cachedInputTokens, int outputTokens) {
    if (inputTokens < 0
        || cachedInputTokens < 0
        || cachedInputTokens > inputTokens
        || outputTokens < 0
        || inputTokens > maximumInputTokens
        || outputTokens > maximumOutputTokens) {
      throw new IllegalArgumentException("Provider usage is outside the approved bounds");
    }
    long uncachedInputTokens = inputTokens - (long) cachedInputTokens;
    long numerator =
        Math.addExact(
            Math.addExact(
                Math.multiplyExact(uncachedInputTokens, inputPriceMicroUsdPerMillionTokens),
                Math.multiplyExact(
                    (long) cachedInputTokens, cachedInputPriceMicroUsdPerMillionTokens)),
            Math.multiplyExact((long) outputTokens, outputPriceMicroUsdPerMillionTokens));
    long result = divideRoundUp(numerator, 1_000_000L);
    if (result > maximumReservationMicroUsd) {
      throw new IllegalArgumentException("Provider usage exceeds the reserved cost");
    }
    return result;
  }

  private static long divideRoundUp(long numerator, long denominator) {
    return Math.addExact(numerator, denominator - 1) / denominator;
  }
}
