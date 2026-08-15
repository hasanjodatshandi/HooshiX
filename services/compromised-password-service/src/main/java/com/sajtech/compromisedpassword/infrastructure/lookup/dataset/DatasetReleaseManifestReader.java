package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DatasetReleaseManifestReader {
  private static final long MAX_MANIFEST_BYTES = 64 * 1024;
  private static final Pattern TOP_LEVEL_SCALAR =
      Pattern.compile(
          "  \\\"([a-z0-9_]+)\\\": (?:\\\"([^\\\"\\r\\n]*)\\\"|([0-9]+)),?");
  private static final Pattern NESTED_SCALAR =
      Pattern.compile("    \\\"([a-z0-9_]+)\\\": \\\"([^\\\"\\r\\n]*)\\\",?");
  private static final Set<String> REQUIRED_TOP_LEVEL =
      Set.of(
          "manifest_version",
          "format_version",
          "sqlite_schema_version",
          "source_kind",
          "hash_mode",
          "retrieval_started_at_utc",
          "retrieval_completed_at_utc",
          "source_artifact_sha256",
          "builder_git_revision",
          "source_line_count",
          "record_count",
          "duplicate_line_count",
          "max_prefix_cardinality",
          "max_serialized_response_bytes",
          "prefix_cardinality_bound",
          "serialized_response_bytes_bound",
          "content_sha256",
          "sqlite_artifact_sha256");
  private static final Set<String> REQUIRED_ACQUISITION = Set.of("name", "version", "sha256");
  private static final Set<String> SOURCE_KINDS =
      Set.of("GENERATED_TEST_FIXTURE", "HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern TOOL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}");
  private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

  DatasetReleaseMetadata read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) <= 0
        || Files.size(path) > MAX_MANIFEST_BYTES) {
      throw new IOException("Dataset release manifest is unavailable");
    }

    List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
    if (lines.size() < 5 || !"{".equals(lines.getFirst()) || !"}".equals(lines.getLast())) {
      throw new IOException("Dataset release manifest structure is invalid");
    }

    Map<String, String> values = new HashMap<>();
    Map<String, String> acquisition = new HashMap<>();
    boolean inAcquisition = false;
    boolean acquisitionSeen = false;
    for (int index = 1; index < lines.size() - 1; index++) {
      String line = lines.get(index);
      if (!inAcquisition && "  \"acquisition_tool\": {".equals(line)) {
        if (acquisitionSeen) {
          throw new IOException("Duplicate dataset manifest field");
        }
        acquisitionSeen = true;
        inAcquisition = true;
        continue;
      }
      if (inAcquisition && "  },".equals(line)) {
        inAcquisition = false;
        continue;
      }

      Matcher matcher = (inAcquisition ? NESTED_SCALAR : TOP_LEVEL_SCALAR).matcher(line);
      if (!matcher.matches()) {
        throw new IOException("Dataset release manifest contains unsupported structure");
      }
      String key = matcher.group(1);
      String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
      Map<String, String> target = inAcquisition ? acquisition : values;
      Set<String> allowed = inAcquisition ? REQUIRED_ACQUISITION : REQUIRED_TOP_LEVEL;
      if (!allowed.contains(key) || target.putIfAbsent(key, value) != null) {
        throw new IOException("Dataset release manifest contains duplicate or unknown field");
      }
    }

    if (inAcquisition
        || !acquisitionSeen
        || !values.keySet().equals(REQUIRED_TOP_LEVEL)
        || !acquisition.keySet().equals(REQUIRED_ACQUISITION)) {
      throw new IOException("Dataset release manifest is incomplete");
    }

    try {
      validateFixedValues(values, acquisition);
      Instant retrievalStarted = Instant.parse(values.get("retrieval_started_at_utc"));
      Instant retrievalCompleted = Instant.parse(values.get("retrieval_completed_at_utc"));
      if (retrievalCompleted.isBefore(retrievalStarted)) {
        throw new IOException("Dataset retrieval interval is invalid");
      }

      long sourceLineCount = positiveLong(values, "source_line_count");
      long recordCount = positiveLong(values, "record_count");
      long duplicateLineCount = nonNegativeLong(values, "duplicate_line_count");
      if (recordCount > sourceLineCount || duplicateLineCount != sourceLineCount - recordCount) {
        throw new IOException("Dataset release counts are inconsistent");
      }

      int maxPrefixCardinality = positiveInt(values, "max_prefix_cardinality");
      long maxSerializedResponseBytes = positiveLong(values, "max_serialized_response_bytes");
      int prefixCardinalityBound = positiveInt(values, "prefix_cardinality_bound");
      long serializedResponseBytesBound = positiveLong(values, "serialized_response_bytes_bound");
      if (maxPrefixCardinality > prefixCardinalityBound
          || maxSerializedResponseBytes > serializedResponseBytesBound) {
        throw new IOException("Dataset release measurements exceed declared compatibility bounds");
      }

      return new DatasetReleaseMetadata(
          retrievalCompleted,
          values.get("source_kind"),
          values.get("sqlite_artifact_sha256"),
          maxPrefixCardinality,
          maxSerializedResponseBytes,
          prefixCardinalityBound,
          serializedResponseBytesBound);
    } catch (DateTimeParseException | NumberFormatException exception) {
      throw new IOException("Dataset release manifest value is invalid", exception);
    }
  }

  private static void validateFixedValues(
      Map<String, String> values, Map<String, String> acquisition) throws IOException {
    if (positiveInt(values, "manifest_version") != 2
        || positiveInt(values, "format_version") != 1
        || positiveInt(values, "sqlite_schema_version") != 1
        || !"SHA1".equals(values.get("hash_mode"))
        || !SOURCE_KINDS.contains(values.get("source_kind"))
        || !SHA256.matcher(values.get("source_artifact_sha256")).matches()
        || !SHA256.matcher(values.get("content_sha256")).matches()
        || !SHA256.matcher(values.get("sqlite_artifact_sha256")).matches()
        || !SHA256.matcher(acquisition.get("sha256")).matches()
        || !TOOL_ID.matcher(acquisition.get("name")).matches()
        || !TOOL_ID.matcher(acquisition.get("version")).matches()
        || !GIT_REVISION.matcher(values.get("builder_git_revision")).matches()) {
      throw new IOException("Dataset release manifest is incompatible");
    }
  }

  private static int positiveInt(Map<String, String> values, String name) throws IOException {
    long value = positiveLong(values, name);
    if (value > Integer.MAX_VALUE) {
      throw new IOException("Dataset manifest integer is out of range");
    }
    return (int) value;
  }

  private static long positiveLong(Map<String, String> values, String name) throws IOException {
    long value = longValue(values, name);
    if (value <= 0) {
      throw new IOException("Dataset manifest value must be positive");
    }
    return value;
  }

  private static long nonNegativeLong(Map<String, String> values, String name) throws IOException {
    long value = longValue(values, name);
    if (value < 0) {
      throw new IOException("Dataset manifest value must not be negative");
    }
    return value;
  }

  private static long longValue(Map<String, String> values, String name) throws IOException {
    String raw = values.get(name);
    if (raw == null) {
      throw new IOException("Dataset manifest field is missing");
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException exception) {
      throw new IOException("Dataset manifest number is invalid", exception);
    }
  }
}
