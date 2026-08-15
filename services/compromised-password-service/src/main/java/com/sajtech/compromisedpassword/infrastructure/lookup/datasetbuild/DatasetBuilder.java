package com.sajtech.compromisedpassword.infrastructure.lookup.datasetbuild;

import com.google.protobuf.CodedOutputStream;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Objects;

public final class DatasetBuilder {
  private static final int FORMAT_VERSION = 1;
  private static final int SQLITE_SCHEMA_VERSION = 1;
  private static final int MAX_SOURCE_LINE_BYTES = 96;
  private static final int SOURCE_READ_BUFFER_BYTES = 64 * 1024;
  private static final int INSERT_BATCH_SIZE = 10_000;
  private static final int SHA1_SUFFIX_ASCII_BYTES = 35;
  private static final HexFormat LOWER_HEX = HexFormat.of();
  private static final String CREATE_TABLE_SQL =
      "CREATE TABLE compromised_password ("
          + "prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),"
          + "hash BLOB NOT NULL CHECK (length(hash) = 20),"
          + "occurrence_count INTEGER NOT NULL "
          + "CHECK (typeof(occurrence_count) = 'integer' AND occurrence_count > 0),"
          + "PRIMARY KEY (prefix, hash)) WITHOUT ROWID";
  private static final String UPSERT_SQL =
      "INSERT INTO compromised_password(prefix, hash, occurrence_count) VALUES (?, ?, ?) "
          + "ON CONFLICT(prefix, hash) DO UPDATE SET occurrence_count = "
          + "compromised_password.occurrence_count + excluded.occurrence_count";

  public DatasetReleaseManifest build(DatasetBuildRequest request) {
    Path temporarySqlite = null;
    Path temporaryManifest = null;
    boolean publishedSqlite = false;
    boolean publishedManifest = false;
    Path sqliteParent =
        Objects.requireNonNull(request.sqliteOutputPath().getParent(), "SQLite output parent");
    Path manifestParent =
        Objects.requireNonNull(request.manifestOutputPath().getParent(), "Manifest output parent");
    try {
      temporarySqlite = Files.createTempFile(sqliteParent, ".compromised-password-", ".sqlite.tmp");
      temporaryManifest =
          Files.createTempFile(manifestParent, ".compromised-password-", ".json.tmp");
      BuildInputResult inputResult = buildSqlite(request, temporarySqlite);
      BuildMetrics metrics = inspectSqlite(temporarySqlite);
      verifyCompatibilityBounds(request, metrics);
      String sqliteArtifactSha256 = digestFile(temporarySqlite);
      DatasetReleaseManifest manifest =
          new DatasetReleaseManifest(
              2,
              FORMAT_VERSION,
              SQLITE_SCHEMA_VERSION,
              request.sourceKind(),
              "SHA1",
              request.retrievalStartedAtUtc(),
              request.retrievalCompletedAtUtc(),
              inputResult.sourceArtifactSha256(),
              request.acquisitionToolName(),
              request.acquisitionToolVersion(),
              request.acquisitionToolSha256(),
              request.builderGitRevision(),
              inputResult.sourceLineCount(),
              metrics.recordCount(),
              inputResult.sourceLineCount() - metrics.recordCount(),
              metrics.maxPrefixCardinality(),
              metrics.maxSerializedResponseBytes(),
              request.maxPrefixCardinalityBound(),
              request.maxSerializedResponseBytesBound(),
              metrics.contentSha256(),
              sqliteArtifactSha256);
      Files.writeString(
          temporaryManifest,
          manifest.toJson(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      publish(temporaryManifest, request.manifestOutputPath());
      publishedManifest = true;
      publish(temporarySqlite, request.sqliteOutputPath());
      publishedSqlite = true;
      return manifest;
    } catch (DatasetBuildException exception) {
      rollbackPublishedOutputs(request, publishedSqlite, publishedManifest);
      throw exception;
    } catch (RuntimeException exception) {
      rollbackPublishedOutputs(request, publishedSqlite, publishedManifest);
      throw exception;
    } catch (IOException exception) {
      rollbackPublishedOutputs(request, publishedSqlite, publishedManifest);
      throw new DatasetBuildException(DatasetBuildException.Reason.PUBLISH_FAILURE);
    } finally {
      deleteIfPresent(temporarySqlite);
      deleteIfPresent(temporaryManifest);
    }
  }

  private static void verifyCompatibilityBounds(DatasetBuildRequest request, BuildMetrics metrics) {
    if (metrics.maxPrefixCardinality() > request.maxPrefixCardinalityBound()
        || metrics.maxSerializedResponseBytes() > request.maxSerializedResponseBytesBound()) {
      throw new DatasetBuildException(DatasetBuildException.Reason.COMPATIBILITY_BOUND_EXCEEDED);
    }
  }

  private BuildInputResult buildSqlite(DatasetBuildRequest request, Path sqlitePath) {
    MessageDigest sourceDigest = sha256Digest();
    long sourceLineCount = 0;
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath)) {
      configureBuildConnection(connection);
      try (Statement statement = connection.createStatement()) {
        statement.execute(CREATE_TABLE_SQL);
      }
      connection.setAutoCommit(false);
      try (PreparedStatement upsert = connection.prepareStatement(UPSERT_SQL);
          InputStream fileInput = Files.newInputStream(request.sourcePath());
          DigestInputStream digestInput =
              new DigestInputStream(
                  new BufferedInputStream(fileInput, SOURCE_READ_BUFFER_BYTES), sourceDigest)) {
        CanonicalLineReader lineReader = new CanonicalLineReader(digestInput);
        int pendingBatch = 0;
        int lineLength;
        while ((lineLength = lineReader.readLine()) >= 0) {
          sourceLineCount++;
          parseAndAddBatchRecord(lineReader.lineBuffer(), lineLength, sourceLineCount, upsert);
          pendingBatch++;
          if (pendingBatch == INSERT_BATCH_SIZE) {
            upsert.executeBatch();
            connection.commit();
            pendingBatch = 0;
          }
        }
        if (pendingBatch > 0) {
          upsert.executeBatch();
          connection.commit();
        }
      }
      if (sourceLineCount == 0) {
        throw new DatasetBuildException(DatasetBuildException.Reason.EMPTY_SOURCE);
      }
      String sourceArtifactSha256 = LOWER_HEX.formatHex(sourceDigest.digest());
      if (!sourceArtifactSha256.equals(request.expectedSourceSha256())) {
        throw new DatasetBuildException(DatasetBuildException.Reason.SOURCE_DIGEST_MISMATCH);
      }
      return new BuildInputResult(sourceLineCount, sourceArtifactSha256);
    } catch (DatasetBuildException exception) {
      throw exception;
    } catch (IOException | SQLException exception) {
      throw new DatasetBuildException(DatasetBuildException.Reason.SQLITE_FAILURE);
    }
  }

  private BuildMetrics inspectSqlite(Path sqlitePath) {
    MessageDigest contentDigest = sha256Digest();
    long recordCount = 0;
    int maxPrefixCardinality = 0;
    long maxSerializedResponseBytes = 0;
    int currentPrefix = -1;
    int currentPrefixCardinality = 0;
    long currentSerializedResponseBytes = 0;
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath);
        Statement statement = connection.createStatement()) {
      try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
        if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
          throw new DatasetBuildException(DatasetBuildException.Reason.INTEGRITY_FAILURE);
        }
      }
      try (ResultSet rows =
          statement.executeQuery(
              "SELECT prefix, hash, occurrence_count FROM compromised_password ORDER BY prefix, hash")) {
        while (rows.next()) {
          int prefix = rows.getInt("prefix");
          byte[] hash = rows.getBytes("hash");
          long occurrenceCount = rows.getLong("occurrence_count");
          if (hash == null
              || hash.length != 20
              || occurrenceCount <= 0
              || prefix != prefixFromHash(hash)) {
            throw new DatasetBuildException(DatasetBuildException.Reason.CONTENT_INVALID);
          }
          if (prefix != currentPrefix) {
            maxPrefixCardinality = Math.max(maxPrefixCardinality, currentPrefixCardinality);
            maxSerializedResponseBytes =
                Math.max(maxSerializedResponseBytes, currentSerializedResponseBytes);
            currentPrefix = prefix;
            currentPrefixCardinality = 0;
            currentSerializedResponseBytes = 0;
          }
          if (currentPrefixCardinality == Integer.MAX_VALUE) {
            throw new DatasetBuildException(DatasetBuildException.Reason.CONTENT_INVALID);
          }
          currentPrefixCardinality++;
          currentSerializedResponseBytes =
              Math.addExact(
                  currentSerializedResponseBytes, serializedMatchContribution(occurrenceCount));
          updateCanonicalDigest(contentDigest, prefix, hash, occurrenceCount);
          recordCount++;
        }
      }
    } catch (DatasetBuildException exception) {
      throw exception;
    } catch (ArithmeticException | SQLException exception) {
      throw new DatasetBuildException(DatasetBuildException.Reason.CONTENT_INVALID);
    }
    if (recordCount == 0) {
      throw new DatasetBuildException(DatasetBuildException.Reason.EMPTY_SOURCE);
    }
    maxPrefixCardinality = Math.max(maxPrefixCardinality, currentPrefixCardinality);
    maxSerializedResponseBytes =
        Math.max(maxSerializedResponseBytes, currentSerializedResponseBytes);
    return new BuildMetrics(
        recordCount,
        maxPrefixCardinality,
        maxSerializedResponseBytes,
        LOWER_HEX.formatHex(contentDigest.digest()));
  }

  private static void configureBuildConnection(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA page_size=4096");
      statement.execute("PRAGMA auto_vacuum=NONE");
      statement.execute("PRAGMA journal_mode=OFF");
      statement.execute("PRAGMA synchronous=OFF");
      statement.execute("PRAGMA temp_store=FILE");
      statement.execute("PRAGMA locking_mode=EXCLUSIVE");
      statement.execute("PRAGMA foreign_keys=ON");
    }
  }

  private static void parseAndAddBatchRecord(
      byte[] line, int lineLength, long lineNumber, PreparedStatement upsert) throws SQLException {
    if (lineLength < 42 || lineLength > 60 || line[40] != ':') {
      throw new DatasetBuildException(DatasetBuildException.Reason.INVALID_SOURCE_LINE, lineNumber);
    }
    byte[] hash = new byte[20];
    for (int index = 0; index < hash.length; index++) {
      int high = hexValue(line[index * 2]);
      int low = hexValue(line[index * 2 + 1]);
      if (high < 0 || low < 0) {
        throw new DatasetBuildException(
            DatasetBuildException.Reason.INVALID_SOURCE_LINE, lineNumber);
      }
      hash[index] = (byte) ((high << 4) | low);
    }
    long occurrenceCount = 0;
    for (int index = 41; index < lineLength; index++) {
      int digit = line[index] - '0';
      if (digit < 0 || digit > 9 || occurrenceCount > (Long.MAX_VALUE - digit) / 10) {
        throw new DatasetBuildException(
            DatasetBuildException.Reason.INVALID_SOURCE_LINE, lineNumber);
      }
      occurrenceCount = occurrenceCount * 10 + digit;
    }
    if (occurrenceCount <= 0) {
      throw new DatasetBuildException(DatasetBuildException.Reason.INVALID_SOURCE_LINE, lineNumber);
    }
    upsert.setInt(1, prefixFromHash(hash));
    upsert.setBytes(2, hash);
    upsert.setLong(3, occurrenceCount);
    upsert.addBatch();
  }

  private static int hexValue(byte value) {
    if (value >= '0' && value <= '9') {
      return value - '0';
    }
    if (value >= 'A' && value <= 'F') {
      return value - 'A' + 10;
    }
    return -1;
  }

  private static int prefixFromHash(byte[] hash) {
    return ((hash[0] & 0xFF) << 12) | ((hash[1] & 0xFF) << 4) | ((hash[2] & 0xF0) >>> 4);
  }

  private static long serializedMatchContribution(long occurrenceCount) {
    int matchSize =
        CodedOutputStream.computeTagSize(
                com.sajtech.compromisedpassword.contract.v1.CompromisedHashMatch
                    .SUFFIX_FIELD_NUMBER)
            + CodedOutputStream.computeUInt32SizeNoTag(SHA1_SUFFIX_ASCII_BYTES)
            + SHA1_SUFFIX_ASCII_BYTES
            + CodedOutputStream.computeTagSize(
                com.sajtech.compromisedpassword.contract.v1.CompromisedHashMatch
                    .OCCURRENCE_COUNT_FIELD_NUMBER)
            + CodedOutputStream.computeUInt64SizeNoTag(occurrenceCount);
    return (long) CodedOutputStream.computeTagSize(LookupPrefixResponse.MATCHES_FIELD_NUMBER)
        + CodedOutputStream.computeUInt32SizeNoTag(matchSize)
        + matchSize;
  }

  private static void updateCanonicalDigest(
      MessageDigest digest, int prefix, byte[] hash, long occurrenceCount) {
    digest.update((byte) (prefix >>> 24));
    digest.update((byte) (prefix >>> 16));
    digest.update((byte) (prefix >>> 8));
    digest.update((byte) prefix);
    digest.update(hash);
    for (int shift = 56; shift >= 0; shift -= 8) {
      digest.update((byte) (occurrenceCount >>> shift));
    }
  }

  private static String digestFile(Path path) {
    MessageDigest digest = sha256Digest();
    try (InputStream input = Files.newInputStream(path)) {
      byte[] buffer = new byte[SOURCE_READ_BUFFER_BYTES];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      return LOWER_HEX.formatHex(digest.digest());
    } catch (IOException exception) {
      throw new DatasetBuildException(DatasetBuildException.Reason.INTEGRITY_FAILURE);
    }
  }

  private static MessageDigest sha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static void publish(Path temporary, Path target) throws IOException {
    try {
      Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, target);
    }
  }

  private static void rollbackPublishedOutputs(
      DatasetBuildRequest request, boolean publishedSqlite, boolean publishedManifest) {
    if (publishedManifest) {
      deleteIfPresent(request.manifestOutputPath());
    }
    if (publishedSqlite) {
      deleteIfPresent(request.sqliteOutputPath());
    }
  }

  private static void deleteIfPresent(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Best-effort cleanup only. The requested final outputs are never overwritten.
    }
  }

  private record BuildInputResult(long sourceLineCount, String sourceArtifactSha256) {}

  private record BuildMetrics(
      long recordCount,
      int maxPrefixCardinality,
      long maxSerializedResponseBytes,
      String contentSha256) {}

  private static final class CanonicalLineReader {
    private final InputStream input;
    private final byte[] inputBuffer = new byte[SOURCE_READ_BUFFER_BYTES];
    private final byte[] lineBuffer = new byte[MAX_SOURCE_LINE_BYTES];
    private int inputPosition;
    private int inputLimit;

    private CanonicalLineReader(InputStream input) {
      this.input = input;
    }

    private byte[] lineBuffer() {
      return lineBuffer;
    }

    private int readLine() throws IOException {
      int length = 0;
      boolean sawByte = false;
      while (true) {
        int value = nextByte();
        if (value == -1) {
          return sawByte ? length : -1;
        }
        sawByte = true;
        if (value == '\n') {
          return length;
        }
        if (value == '\r') {
          if (nextByte() != '\n') {
            throw new DatasetBuildException(DatasetBuildException.Reason.INVALID_SOURCE_LINE);
          }
          return length;
        }
        if (length == lineBuffer.length || value > 0x7F) {
          throw new DatasetBuildException(DatasetBuildException.Reason.INVALID_SOURCE_LINE);
        }
        lineBuffer[length++] = (byte) value;
      }
    }

    private int nextByte() throws IOException {
      if (inputPosition == inputLimit) {
        inputLimit = input.read(inputBuffer);
        inputPosition = 0;
        if (inputLimit == -1) {
          return -1;
        }
      }
      return inputBuffer[inputPosition++] & 0xFF;
    }
  }
}
