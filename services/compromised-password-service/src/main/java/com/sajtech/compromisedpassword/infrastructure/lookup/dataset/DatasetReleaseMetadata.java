package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.time.Instant;
import java.util.Objects;

public record DatasetReleaseMetadata(
    Instant retrievalCompletedAtUtc,
    String sourceKind,
    String sqliteArtifactSha256,
    int maxPrefixCardinality,
    long maxSerializedResponseBytes,
    int prefixCardinalityBound,
    long serializedResponseBytesBound) {
  public DatasetReleaseMetadata {
    Objects.requireNonNull(retrievalCompletedAtUtc, "retrievalCompletedAtUtc");
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(sqliteArtifactSha256, "sqliteArtifactSha256");
  }
}
