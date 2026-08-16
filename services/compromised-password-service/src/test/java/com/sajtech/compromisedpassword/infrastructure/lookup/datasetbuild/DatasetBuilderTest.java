package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.contract.v1.CompromisedHashMatch;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class DatasetBuilderTest {
  private static final Instant RETRIEVAL_START = Instant.parse("2026-08-14T10:00:00Z");
  private static final Instant RETRIEVAL_END = Instant.parse("2026-08-14T10:30:00Z");
  private static final String ACQUISITION_TOOL_SHA256 = "b".repeat(64);
  private static final String BUILD_GIT_REVISION = "c".repeat(40);
  private static final int PREFIX_BOUND = 64;
  private static final long RESPONSE_BOUND = 16_384;

  @TempDir Path tempDirectory;

  @Test
  void buildsCanonicalSqliteAndReleaseManifestFromGeneratedFixture() throws Exception {
    Path source = tempDirectory.resolve("source.txt");
    String fixture =
        String.join(
                "\r\n",
                "ABCDE" + "1".repeat(35) + ":2",
                "ABCDE" + "1".repeat(35) + ":3",
                "ABCDE" + "2".repeat(35) + ":7",
                "12345" + "A".repeat(35) + ":11")
            + "\r\n";
    Files.writeString(source, fixture, StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("compromised-password.sqlite");
    Path manifest = tempDirectory.resolve("compromised-password.manifest.json");

    DatasetReleaseManifest result =
        new DatasetBuilder().build(request(source, sqlite, manifest, sha256(source)));
    int expectedMaxResponseBytes =
        LookupPrefixResponse.newBuilder()
            .addMatches(
                CompromisedHashMatch.newBuilder().setSuffix("1".repeat(35)).setOccurrenceCount(5))
            .addMatches(
                CompromisedHashMatch.newBuilder().setSuffix("2".repeat(35)).setOccurrenceCount(7))
            .build()
            .getSerializedSize();

    assertThat(result.manifestVersion()).isEqualTo(2);
    assertThat(result.sourceKind()).isEqualTo(DatasetSourceKind.GENERATED_TEST_FIXTURE);
    assertThat(result.sourceLineCount()).isEqualTo(4);
    assertThat(result.recordCount()).isEqualTo(3);
    assertThat(result.duplicateLineCount()).isEqualTo(1);
    assertThat(result.maxPrefixCardinality()).isEqualTo(2);
    assertThat(result.maxSerializedResponseBytes()).isEqualTo(expectedMaxResponseBytes);
    assertThat(result.prefixCardinalityBound()).isEqualTo(PREFIX_BOUND);
    assertThat(result.serializedResponseBytesBound()).isEqualTo(RESPONSE_BOUND);
    assertThat(result.sqliteArtifactSha256()).isEqualTo(sha256(sqlite));
    assertThat(result.contentSha256()).matches("[0-9a-f]{64}");
    assertThat(Files.readString(manifest))
        .contains("\"manifest_version\": 2")
        .contains("\"source_kind\": \"GENERATED_TEST_FIXTURE\"")
        .contains("\"hash_mode\": \"SHA1\"")
        .doesNotContain("HIBP_PWNED_PASSWORDS_COMPLETE_DOWNLOAD")
        .contains("\"record_count\": 3")
        .contains("\"max_prefix_cardinality\": 2")
        .contains("\"prefix_cardinality_bound\": " + PREFIX_BOUND)
        .contains("\"serialized_response_bytes_bound\": " + RESPONSE_BOUND)
        .contains("\"sqlite_artifact_sha256\": \"" + sha256(sqlite) + "\"");

    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlite);
        Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "SELECT hex(hash), occurrence_count FROM compromised_password "
                    + "WHERE prefix = 703710 ORDER BY hash")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("ABCDE" + "1".repeat(35));
      assertThat(rows.getLong(2)).isEqualTo(5);
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("ABCDE" + "2".repeat(35));
      assertThat(rows.getLong(2)).isEqualTo(7);
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void rejectsMeasuredPrefixCardinalityAboveApprovedBoundWithoutPublishingOutputs()
      throws Exception {
    Path source = tempDirectory.resolve("too-many-prefix-rows.txt");
    Files.writeString(
        source,
        "ABCDE" + "1".repeat(35) + ":1\n" + "ABCDE" + "2".repeat(35) + ":1\n",
        StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("too-many-prefix.sqlite");
    Path manifest = tempDirectory.resolve("too-many-prefix.json");

    assertThatThrownBy(
            () ->
                new DatasetBuilder()
                    .build(request(source, sqlite, manifest, sha256(source), 1, RESPONSE_BOUND)))
        .isInstanceOf(DatasetBuildException.class)
        .extracting(exception -> ((DatasetBuildException) exception).reason())
        .isEqualTo(DatasetBuildException.Reason.COMPATIBILITY_BOUND_EXCEEDED);
    assertThat(sqlite).doesNotExist();
    assertThat(manifest).doesNotExist();
  }

  @Test
  void rejectsMeasuredResponseSizeAboveApprovedBoundWithoutPublishingOutputs() throws Exception {
    Path source = tempDirectory.resolve("too-large-response.txt");
    Files.writeString(source, "ABCDE" + "1".repeat(35) + ":1\n", StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("too-large-response.sqlite");
    Path manifest = tempDirectory.resolve("too-large-response.json");

    assertThatThrownBy(
            () ->
                new DatasetBuilder()
                    .build(request(source, sqlite, manifest, sha256(source), 64, 1)))
        .isInstanceOf(DatasetBuildException.class)
        .extracting(exception -> ((DatasetBuildException) exception).reason())
        .isEqualTo(DatasetBuildException.Reason.COMPATIBILITY_BOUND_EXCEEDED);
    assertThat(sqlite).doesNotExist();
    assertThat(manifest).doesNotExist();
  }

  @Test
  void rejectsZeroCountWithoutPublishingOutputs() throws Exception {
    Path source = tempDirectory.resolve("zero-count.txt");
    Files.writeString(source, "ABCDE" + "3".repeat(35) + ":0\r\n", StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("zero-count.sqlite");
    Path manifest = tempDirectory.resolve("zero-count.json");

    assertThatThrownBy(
            () -> new DatasetBuilder().build(request(source, sqlite, manifest, sha256(source))))
        .isInstanceOf(DatasetBuildException.class)
        .extracting(exception -> ((DatasetBuildException) exception).reason())
        .isEqualTo(DatasetBuildException.Reason.INVALID_SOURCE_LINE);
    assertThat(sqlite).doesNotExist();
    assertThat(manifest).doesNotExist();
  }

  @Test
  void rejectsNonCanonicalLowercaseHashWithoutPublishingOutputs() throws Exception {
    Path source = tempDirectory.resolve("lowercase.txt");
    Files.writeString(source, "abcde" + "4".repeat(35) + ":1\n", StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("lowercase.sqlite");
    Path manifest = tempDirectory.resolve("lowercase.json");

    assertThatThrownBy(
            () -> new DatasetBuilder().build(request(source, sqlite, manifest, sha256(source))))
        .isInstanceOf(DatasetBuildException.class)
        .extracting(exception -> ((DatasetBuildException) exception).reason())
        .isEqualTo(DatasetBuildException.Reason.INVALID_SOURCE_LINE);
    assertThat(sqlite).doesNotExist();
    assertThat(manifest).doesNotExist();
  }

  @Test
  void rejectsSourceDigestMismatchWithoutPublishingOutputs() throws Exception {
    Path source = tempDirectory.resolve("digest.txt");
    Files.writeString(source, "ABCDE" + "5".repeat(35) + ":1\r\n", StandardCharsets.US_ASCII);
    Path sqlite = tempDirectory.resolve("digest.sqlite");
    Path manifest = tempDirectory.resolve("digest.json");

    assertThatThrownBy(
            () -> new DatasetBuilder().build(request(source, sqlite, manifest, "0".repeat(64))))
        .isInstanceOf(DatasetBuildException.class)
        .extracting(exception -> ((DatasetBuildException) exception).reason())
        .isEqualTo(DatasetBuildException.Reason.SOURCE_DIGEST_MISMATCH);
    assertThat(sqlite).doesNotExist();
    assertThat(manifest).doesNotExist();
  }

  private DatasetBuildRequest request(
      Path source, Path sqlite, Path manifest, String expectedSourceSha256) {
    return request(source, sqlite, manifest, expectedSourceSha256, PREFIX_BOUND, RESPONSE_BOUND);
  }

  private DatasetBuildRequest request(
      Path source,
      Path sqlite,
      Path manifest,
      String expectedSourceSha256,
      int prefixBound,
      long responseBound) {
    return new DatasetBuildRequest(
        DatasetSourceKind.GENERATED_TEST_FIXTURE,
        source,
        sqlite,
        manifest,
        expectedSourceSha256,
        RETRIEVAL_START,
        RETRIEVAL_END,
        "generated-test-fixture",
        "1.0.0",
        ACQUISITION_TOOL_SHA256,
        BUILD_GIT_REVISION,
        prefixBound,
        responseBound);
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
