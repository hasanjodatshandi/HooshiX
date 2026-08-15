package com.sajtech.compromisedpassword.domain.lookup.valueobject;

import java.util.Objects;
import java.util.regex.Pattern;

public final class CompromisedHashMatch {
  private static final Pattern CANONICAL_SUFFIX = Pattern.compile("[0-9A-F]{35}");
  private final String suffix;
  private final long occurrenceCount;

  public CompromisedHashMatch(String suffix, long occurrenceCount) {
    this.suffix = Objects.requireNonNull(suffix, "suffix");
    if (!CANONICAL_SUFFIX.matcher(suffix).matches()) {
      throw new IllegalArgumentException(
          "SHA-1 suffix must be 35 uppercase hexadecimal characters");
    }
    if (occurrenceCount <= 0) {
      throw new IllegalArgumentException("Occurrence count must be positive");
    }
    this.occurrenceCount = occurrenceCount;
  }

  public String suffix() {
    return suffix;
  }

  public long occurrenceCount() {
    return occurrenceCount;
  }
}
