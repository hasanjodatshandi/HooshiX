package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.application.lookup.LookupUnavailableException;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SqliteCompromisedPasswordRepositoryIntegrationTest {
  private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");
  private static final String SHA256_A = "a".repeat(64);
  private static final String SHA256_B = "b".repeat(64);
  private static final String GIT_REVISION = "c".repeat(40);

  @TempDir Path tempDirectory;

  @Test
  void returnsCanonicalSuffixAndPositiveCountFromReadOnlyDataset() throws Exception {
    Path dataset = createDataset("ABCDE" + "1".repeat(35), 42L);
    DatasetSettings settings =
        createSettings(dataset, NOW.minusSeconds(3600), 1, 4096, 1, 128, "valid");
    DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));
    SqliteCompromisedPasswordRepository repository =
        new SqliteCompromisedPasswordRepository(guard, settings.maxPrefixCardinality());

    var matches = repository.findByPrefix(Sha1Prefix.parse("ABCDE"));

    assertThat(guard.state()).isEqualTo(DatasetState.READY);
    assertThat(matches).hasSize(1);
    assertThat(matches.getFirst().suffix()).isEqualTo("1".repeat(35));
    assertThat(matches.getFirst().occurrenceCount()).isEqualTo(42L);

    try (Connection connection = guard.openReadOnlyConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () ->
                  statement.executeUpdate("DELETE FROM compromised_password WHERE prefix = 703710"))
          .isInstanceOf(SQLException.class);
    }
  }

  @Test
  void staleDatasetFailsClosed() throws Exception {
    Path dataset = createDataset("ABCDE" + "2".repeat(35), 7L);
    DatasetSettings settings =
        createSettings(dataset, NOW.minusSeconds(36L * 86_400), 1, 4096, 1, 128, "stale");
    DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));
    SqliteCompromisedPasswordRepository repository =
        new SqliteCompromisedPasswordRepository(guard, settings.maxPrefixCardinality());

    assertThat(guard.state()).isEqualTo(DatasetState.STALE);
    assertThatThrownBy(() -> repository.findByPrefix(Sha1Prefix.parse("ABCDE")))
        .isInstanceOf(LookupUnavailableException.class);
  }

  @Test
  void manifestDigestMismatchFailsClosedBeforeManifestTrust() throws Exception {
    Path dataset = createDataset("ABCDE" + "3".repeat(35), 1L);
    DatasetSettings valid = createSettings(dataset, NOW, 1, 4096, 1, 128, "digest");
    DatasetSettings invalid =
        new DatasetSettings(
            valid.path(),
            valid.manifestPath(),
            "0".repeat(64),
            valid.requiredSourceKind(),
            valid.maxPrefixCardinality(),
            valid.maxSerializedResponseBytes());

    DatasetGuard guard = new DatasetGuard(invalid, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(guard.state()).isEqualTo(DatasetState.CORRUPT);
    assertThatThrownBy(guard::openReadOnlyConnection).isInstanceOf(LookupUnavailableException.class);
  }

  @Test
  void malformedManifestWithMatchingDigestFailsClosed() throws Exception {
    Path dataset = createDataset("ABCDE" + "4".repeat(35), 1L);
    Path manifest = tempDirectory.resolve("malformed-manifest.json");
    Files.writeString(
        manifest,
        "{\n  \"manifest_version\": 2,\n  \"unknown\": 1\n}\n",
        StandardCharsets.UTF_8);
    DatasetSettings settings =
        new DatasetSettings(
            dataset,
            manifest,
            sha256(manifest),
            "GENERATED_TEST_FIXTURE",
            1,
            4096);

    DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(guard.state()).isEqualTo(DatasetState.INCOMPATIBLE);
    assertThatThrownBy(guard::openReadOnlyConnection).isInstanceOf(LookupUnavailableException.class);
  }

  @Test
  void runtimeCardinalityGuardFailsClosedInsteadOfTruncating() throws Exception {
    Path dataset = createDataset("ABCDE" + "5".repeat(35), 1L);
    append(dataset, "ABCDE" + "6".repeat(35), 2L);
    DatasetSettings settings = createSettings(dataset, NOW, 1, 4096, 1, 128, "cardinality");
    DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));
    SqliteCompromisedPasswordRepository repository =
        new SqliteCompromisedPasswordRepository(guard, settings.maxPrefixCardinality());

    assertThat(guard.state()).isEqualTo(DatasetState.READY);
    assertThatThrownBy(() -> repository.findByPrefix(Sha1Prefix.parse("ABCDE")))
        .isInstanceOf(LookupUnavailableException.class)
        .hasMessageContaining("response bound");
  }

  private Path createDataset(String hash, long count) throws SQLException {
    Path path = tempDirectory.resolve("compromised-password-" + System.nanoTime() + ".sqlite");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE compromised_password ("
              + "prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),"
              + "hash BLOB NOT NULL CHECK (length(hash) = 20),"
              + "occurrence_count INTEGER NOT NULL CHECK "
              + "(typeof(occurrence_count) = 'integer' AND occurrence_count > 0),"
              + "PRIMARY KEY (prefix, hash)) WITHOUT ROWID");
    }
    append(path, hash, count);
    return path;
  }

  private void append(Path path, String hash, long count) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO compromised_password(prefix, hash, occurrence_count) VALUES (?, ?, ?)")) {
      statement.setInt(1, Integer.parseInt(hash.substring(0, 5), 16));
      statement.setBytes(2, HexFormat.of().parseHex(hash));
      statement.setLong(3, count);
      statement.executeUpdate();
    }
  }

  private DatasetSettings createSettings(
      Path dataset,
      Instant retrievalCompleted,
      int runtimePrefixBound,
      long runtimeResponseBound,
      int manifestPrefixMeasurement,
      long manifestResponseMeasurement,
      String suffix)
      throws IOException {
    Path manifest = tempDirectory.resolve("release-manifest-" + suffix + ".json");
    String content =
        "{\n"
            + "  \"manifest_version\": 2,\n"
            + "  \"format_version\": 1,\n"
            + "  \"sqlite_schema_version\": 1,\n"
            + "  \"source_kind\": \"GENERATED_TEST_FIXTURE\",\n"
            + "  \"hash_mode\": \"SHA1\",\n"
            + "  \"retrieval_started_at_utc\": \""
            + retrievalCompleted.minusSeconds(60)
            + "\",\n"
            + "  \"retrieval_completed_at_utc\": \""
            + retrievalCompleted
            + "\",\n"
            + "  \"source_artifact_sha256\": \""
            + SHA256_A
            + "\",\n"
            + "  \"acquisition_tool\": {\n"
            + "    \"name\": \"generated-test-fixture\",\n"
            + "    \"version\": \"1.0.0\",\n"
            + "    \"sha256\": \""
            + SHA256_B
            + "\"\n"
            + "  },\n"
            + "  \"builder_git_revision\": \""
            + GIT_REVISION
            + "\",\n"
            + "  \"source_line_count\": 1,\n"
            + "  \"record_count\": 1,\n"
            + "  \"duplicate_line_count\": 0,\n"
            + "  \"max_prefix_cardinality\": "
            + manifestPrefixMeasurement
            + ",\n"
            + "  \"max_serialized_response_bytes\": "
            + manifestResponseMeasurement
            + ",\n"
            + "  \"prefix_cardinality_bound\": "
            + runtimePrefixBound
            + ",\n"
            + "  \"serialized_response_bytes_bound\": "
            + runtimeResponseBound
            + ",\n"
            + "  \"content_sha256\": \""
            + SHA256_B
            + "\",\n"
            + "  \"sqlite_artifact_sha256\": \""
            + sha256(dataset)
            + "\"\n"
            + "}\n";
    Files.writeString(manifest, content, StandardCharsets.UTF_8);
    return new DatasetSettings(
        dataset,
        manifest,
        sha256(manifest),
        "GENERATED_TEST_FIXTURE",
        runtimePrefixBound,
        runtimeResponseBound);
  }

  private static String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
