package com.sajtech.conversation.application.model;

import java.util.Objects;
import java.util.regex.Pattern;

public record ModelExecutionPolicy(
    String modelAlias, String promptVersion, String priceVersion, long maximumReservationMicroUsd) {
  private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");
  private static final Pattern SEMVER = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+$");
  private static final Pattern PRICE_VERSION = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

  public ModelExecutionPolicy {
    Objects.requireNonNull(modelAlias);
    Objects.requireNonNull(promptVersion);
    Objects.requireNonNull(priceVersion);
    if (!ALIAS.matcher(modelAlias).matches()
        || !SEMVER.matcher(promptVersion).matches()
        || !PRICE_VERSION.matcher(priceVersion).matches()
        || maximumReservationMicroUsd <= 0) {
      throw new IllegalArgumentException("Model execution policy reservation is invalid");
    }
  }
}
