package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DatasetReleaseManifestReader {
  private static final long MAX_MANIFEST_BYTES = 64 * 1024;
  private static final Pattern SCALAR =
      Pattern.compile(
          "\\s*\\\"([a-z0-9_]+)\\\"\\s*:\\s*(?:\\\"([^\\\"\\r\\n]*)\\\"|([0-9]+))\\s*,?\\s*");
  private static final Set<String> REQUIRED =
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

  DatasetReleaseMetadata read(Path path) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) <= 0
        || Files.size(path) > MAX_MANIFEST_BYTES) {
      throw new IOException("Dataset release manifest is unavailable");
    }
    String json = Files.readString(path, StandardCharsets.UTF_8);
    Map<String, String> values = new HashMap<>();
    for (String line : json.split("\\R")) {
      Matcher matcher = SCALAR.matcher(line);
      if (matcher.matches()) {
        String key = matcher.group(1);
        if (values.putIfAbsent(key, matcher.group(2) != null ? matcher.group(2) : matcher.group(3))
            != null) {
          throw new IOException("Duplicate dataset manifest field");
        }
      }
    }
    if (!values.keySet().containsAll(REQUIRED)
        || integer(values, "manifest_version") != 2
        || integer(values, "format_version") != 1
        || integer(values, "sqlite_schema_version") != 1
        || !"SHA1".equals(values.get("hash_mode"))) {
      throw new IOException("Dataset release manifest is incompatible");
    }
    String sha256 = values.get("sqlite_artifact_sha256");
    if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
      throw new IOException("Dataset release digest is invalid");
    }
    try {
      return new DatasetReleaseMetadata(
          Instant.parse(values.get("retrieval_completed_at_utc")),
          values.get("source_kind"),
          sha256,
          integer(values, "max_prefix_cardinality"),
          longValue(values, "max_serialized_response_bytes"),
          integer(values, "prefix_cardinality_bound"),
          longValue(values, "serialized_response_bytes_bound"));
    } catch (DateTimeParseException | NumberFormatException exception) {
      throw new IOException("Dataset release manifest value is invalid", exception);
    }
  }

  private static int integer(Map<String, String> values, String name) throws IOException {
    long value = longValue(values, name);
    if (value <= 0 || value > Integer.MAX_VALUE) {
      throw new IOException("Dataset manifest integer is out of range");
    }
    return (int) value;
  }

  private static long longValue(Map<String, String> values, String name) throws IOException {
    String raw = values.get(name);
    if (raw == null) {
      throw new IOException("Dataset manifest field is missing");
    }
    try {
      long value = Long.parseLong(raw);
      if (value <= 0) {
        throw new IOException("Dataset manifest value must be positive");
      }
      return value;
    } catch (NumberFormatException exception) {
      throw new IOException("Dataset manifest number is invalid", exception);
    }
  }
}
