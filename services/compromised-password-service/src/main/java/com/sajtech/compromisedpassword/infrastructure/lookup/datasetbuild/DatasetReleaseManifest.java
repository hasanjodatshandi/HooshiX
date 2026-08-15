package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

import java.time.Instant;

public record DatasetReleaseManifest(
    int manifestVersion,
    int formatVersion,
    int sqliteSchemaVersion,
    DatasetSourceKind sourceKind,
    String hashMode,
    Instant retrievalStartedAtUtc,
    Instant retrievalCompletedAtUtc,
    String sourceArtifactSha256,
    String acquisitionToolName,
    String acquisitionToolVersion,
    String acquisitionToolSha256,
    String builderGitRevision,
    long sourceLineCount,
    long recordCount,
    long duplicateLineCount,
    int maxPrefixCardinality,
    long maxSerializedResponseBytes,
    String contentSha256,
    String sqliteArtifactSha256) {

  public String toJson() {
    return "{\n"
        + "  \"manifest_version\": "
        + manifestVersion
        + ",\n"
        + "  \"format_version\": "
        + formatVersion
        + ",\n"
        + "  \"sqlite_schema_version\": "
        + sqliteSchemaVersion
        + ",\n"
        + "  \"source_kind\": \""
        + sourceKind.name()
        + "\",\n"
        + "  \"hash_mode\": \""
        + hashMode
        + "\",\n"
        + "  \"retrieval_started_at_utc\": \""
        + retrievalStartedAtUtc
        + "\",\n"
        + "  \"retrieval_completed_at_utc\": \""
        + retrievalCompletedAtUtc
        + "\",\n"
        + "  \"source_artifact_sha256\": \""
        + sourceArtifactSha256
        + "\",\n"
        + "  \"acquisition_tool\": {\n"
        + "    \"name\": \""
        + acquisitionToolName
        + "\",\n"
        + "    \"version\": \""
        + acquisitionToolVersion
        + "\",\n"
        + "    \"sha256\": \""
        + acquisitionToolSha256
        + "\"\n"
        + "  },\n"
        + "  \"builder_git_revision\": \""
        + builderGitRevision
        + "\",\n"
        + "  \"source_line_count\": "
        + sourceLineCount
        + ",\n"
        + "  \"record_count\": "
        + recordCount
        + ",\n"
        + "  \"duplicate_line_count\": "
        + duplicateLineCount
        + ",\n"
        + "  \"max_prefix_cardinality\": "
        + maxPrefixCardinality
        + ",\n"
        + "  \"max_serialized_response_bytes\": "
        + maxSerializedResponseBytes
        + ",\n"
        + "  \"content_sha256\": \""
        + contentSha256
        + "\",\n"
        + "  \"sqlite_artifact_sha256\": \""
        + sqliteArtifactSha256
        + "\"\n"
        + "}\n";
  }
}
