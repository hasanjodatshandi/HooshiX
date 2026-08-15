package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public record DatasetBuildRequest(
    DatasetSourceKind sourceKind,
    Path sourcePath,
    Path sqliteOutputPath,
    Path manifestOutputPath,
    String expectedSourceSha256,
    Instant retrievalStartedAtUtc,
    Instant retrievalCompletedAtUtc,
    String acquisitionToolName,
    String acquisitionToolVersion,
    String acquisitionToolSha256,
    String builderGitRevision) {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");
  private static final Pattern TOOL_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+:/-]{0,127}");
  private static final Set<String> REQUIRED_OPTIONS =
      Set.of(
          "--source-kind",
          "--input",
          "--output",
          "--manifest",
          "--source-sha256",
          "--retrieval-started-at",
          "--retrieval-completed-at",
          "--acquisition-tool-name",
          "--acquisition-tool-version",
          "--acquisition-tool-sha256",
          "--build-git-revision");

  public DatasetBuildRequest {
    Objects.requireNonNull(sourceKind, "sourceKind");
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(sqliteOutputPath, "sqliteOutputPath");
    Objects.requireNonNull(manifestOutputPath, "manifestOutputPath");
    Objects.requireNonNull(expectedSourceSha256, "expectedSourceSha256");
    Objects.requireNonNull(retrievalStartedAtUtc, "retrievalStartedAtUtc");
    Objects.requireNonNull(retrievalCompletedAtUtc, "retrievalCompletedAtUtc");
    Objects.requireNonNull(acquisitionToolName, "acquisitionToolName");
    Objects.requireNonNull(acquisitionToolVersion, "acquisitionToolVersion");
    Objects.requireNonNull(acquisitionToolSha256, "acquisitionToolSha256");
    Objects.requireNonNull(builderGitRevision, "builderGitRevision");

    sourcePath = sourcePath.toAbsolutePath().normalize();
    sqliteOutputPath = normalizeOutputPath(sqliteOutputPath);
    manifestOutputPath = normalizeOutputPath(manifestOutputPath);

    if (!Files.isRegularFile(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Source must be a regular local file, not a symlink");
    }
    if (sourcePath.equals(sqliteOutputPath)
        || sourcePath.equals(manifestOutputPath)
        || sqliteOutputPath.equals(manifestOutputPath)) {
      throw new IllegalArgumentException("Source, SQLite output, and manifest must be distinct");
    }
    if (!SHA256.matcher(expectedSourceSha256).matches()) {
      throw new IllegalArgumentException("Source SHA-256 must be lowercase hexadecimal");
    }
    if (!SHA256.matcher(acquisitionToolSha256).matches()) {
      throw new IllegalArgumentException("Acquisition-tool SHA-256 must be lowercase hexadecimal");
    }
    if (!GIT_REVISION.matcher(builderGitRevision).matches()) {
      throw new IllegalArgumentException("Builder Git revision must be a lowercase 40-hex commit");
    }
    if (!TOOL_TOKEN.matcher(acquisitionToolName).matches()
        || !TOOL_TOKEN.matcher(acquisitionToolVersion).matches()) {
      throw new IllegalArgumentException("Acquisition-tool identity must use bounded safe tokens");
    }
    if (retrievalStartedAtUtc.isAfter(retrievalCompletedAtUtc)) {
      throw new IllegalArgumentException("Retrieval start must not be after retrieval completion");
    }
  }

  public static DatasetBuildRequest fromArgs(String[] args) {
    Objects.requireNonNull(args, "args");
    if (args.length != REQUIRED_OPTIONS.size() * 2) {
      throw new IllegalArgumentException("All dataset-build options are required exactly once");
    }
    Map<String, String> options = new LinkedHashMap<>();
    for (int index = 0; index < args.length; index += 2) {
      String key = args[index];
      if (!REQUIRED_OPTIONS.contains(key) || options.putIfAbsent(key, args[index + 1]) != null) {
        throw new IllegalArgumentException("Unknown or duplicate dataset-build option");
      }
    }
    if (!options.keySet().equals(REQUIRED_OPTIONS)) {
      throw new IllegalArgumentException("All dataset-build options are required exactly once");
    }

    try {
      return new DatasetBuildRequest(
          DatasetSourceKind.valueOf(options.get("--source-kind")),
          Path.of(options.get("--input")),
          Path.of(options.get("--output")),
          Path.of(options.get("--manifest")),
          options.get("--source-sha256"),
          Instant.parse(options.get("--retrieval-started-at")),
          Instant.parse(options.get("--retrieval-completed-at")),
          options.get("--acquisition-tool-name"),
          options.get("--acquisition-tool-version"),
          options.get("--acquisition-tool-sha256"),
          options.get("--build-git-revision"));
    } catch (DateTimeParseException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid dataset-build option value");
    }
  }

  private static Path normalizeOutputPath(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    String value = normalized.toString();
    if (value.indexOf('\0') >= 0 || value.contains("?") || value.contains("#")) {
      throw new IllegalArgumentException("Output path must not contain SQLite URI controls");
    }
    Path parent = normalized.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      throw new IllegalArgumentException("Output parent directory must already exist");
    }
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Output files must not already exist");
    }
    return normalized;
  }
}
