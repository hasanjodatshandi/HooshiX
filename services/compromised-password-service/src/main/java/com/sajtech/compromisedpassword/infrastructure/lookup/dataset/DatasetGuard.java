package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import com.sajtech.compromisedpassword.application.lookup.LookupUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import org.sqlite.SQLiteConfig;

public final class DatasetGuard {
  private static final Duration MAX_DATASET_AGE = Duration.ofDays(35);
  private static final String EXPECTED_SCHEMA_SQL =
      "CREATE TABLE compromised_password (prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),hash BLOB NOT NULL CHECK (length(hash) = 20),occurrence_count INTEGER NOT NULL CHECK (typeof(occurrence_count) = 'integer' AND occurrence_count > 0),PRIMARY KEY (prefix, hash)) WITHOUT ROWID";
  private final DatasetSettings settings;
  private final Clock clock;
  private final DatasetReleaseMetadata metadata;
  private final DatasetState staticState;

  public DatasetGuard(DatasetSettings settings, Clock clock) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    Verification verification = verifyStaticState();
    this.metadata = verification.metadata();
    this.staticState = verification.state();
  }

  public DatasetState state() {
    if (staticState != DatasetState.READY || metadata == null) {
      return staticState;
    }
    Instant now = clock.instant();
    if (metadata.retrievalCompletedAtUtc().isAfter(now)) {
      return DatasetState.INCOMPATIBLE;
    }
    if (Duration.between(metadata.retrievalCompletedAtUtc(), now).compareTo(MAX_DATASET_AGE) > 0) {
      return DatasetState.STALE;
    }
    return DatasetState.READY;
  }

  public Connection openReadOnlyConnection() {
    DatasetState current = state();
    if (current != DatasetState.READY) {
      throw new LookupUnavailableException("Dataset is not ready: " + current.name());
    }
    return openConnection();
  }

  private Verification verifyStaticState() {
    if (!Files.isRegularFile(settings.path(), LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(settings.path())) {
      return new Verification(DatasetState.MISSING, null);
    }
    try {
      DatasetReleaseMetadata release =
          new DatasetReleaseManifestReader().read(settings.manifestPath());
      if (!settings.requiredSourceKind().equals(release.sourceKind())
          || release.maxPrefixCardinality() > settings.maxPrefixCardinality()
          || release.maxSerializedResponseBytes() > settings.maxSerializedResponseBytes()
          || release.prefixCardinalityBound() > settings.maxPrefixCardinality()
          || release.serializedResponseBytesBound() > settings.maxSerializedResponseBytes()) {
        return new Verification(DatasetState.INCOMPATIBLE, release);
      }
      if (!digest().equals(release.sqliteArtifactSha256())) {
        return new Verification(DatasetState.CORRUPT, release);
      }
      try (Connection connection = openConnection();
          Statement statement = connection.createStatement()) {
        if (!integrityIsValid(statement)) {
          return new Verification(DatasetState.CORRUPT, release);
        }
        if (!schemaIsCompatible(statement)) {
          return new Verification(DatasetState.INCOMPATIBLE, release);
        }
      }
      return new Verification(DatasetState.READY, release);
    } catch (IOException exception) {
      return new Verification(DatasetState.INCOMPATIBLE, null);
    } catch (SQLException | RuntimeException exception) {
      return new Verification(DatasetState.UNAVAILABLE, null);
    }
  }

  private static boolean integrityIsValid(Statement statement) throws SQLException {
    try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
      return integrity.next() && "ok".equalsIgnoreCase(integrity.getString(1));
    }
  }

  private static boolean schemaIsCompatible(Statement statement) throws SQLException {
    try (ResultSet objects =
        statement.executeQuery(
            "SELECT type, name, sql FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' ORDER BY name")) {
      if (!objects.next()
          || !"table".equals(objects.getString("type"))
          || !"compromised_password".equals(objects.getString("name"))
          || !normalizeSql(EXPECTED_SCHEMA_SQL).equals(normalizeSql(objects.getString("sql")))
          || objects.next()) {
        return false;
      }
    }
    try (ResultSet columns = statement.executeQuery("PRAGMA table_info(compromised_password)")) {
      return matchesColumn(columns, "prefix", "INTEGER", 1, 1)
          && matchesColumn(columns, "hash", "BLOB", 1, 2)
          && matchesColumn(columns, "occurrence_count", "INTEGER", 1, 0)
          && !columns.next();
    }
  }

  private static boolean matchesColumn(
      ResultSet columns, String name, String type, int notNull, int primaryKey)
      throws SQLException {
    return columns.next()
        && name.equals(columns.getString("name"))
        && type.equalsIgnoreCase(columns.getString("type"))
        && columns.getInt("notnull") == notNull
        && columns.getInt("pk") == primaryKey;
  }

  private static String normalizeSql(String sql) {
    return sql == null ? "" : sql.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
  }

  private Connection openConnection() {
    SQLiteConfig config = new SQLiteConfig();
    config.setReadOnly(true);
    config.enableLoadExtension(false);
    config.setSharedCache(false);
    String jdbcUrl = "jdbc:sqlite:file:" + settings.path() + "?immutable=1&mode=ro";
    Connection connection = null;
    try {
      connection = config.createConnection(jdbcUrl);
      try (Statement statement = connection.createStatement()) {
        statement.execute("PRAGMA query_only=ON");
      }
      return connection;
    } catch (SQLException exception) {
      if (connection != null) {
        try {
          connection.close();
        } catch (SQLException closeFailure) {
          exception.addSuppressed(closeFailure);
        }
      }
      throw new LookupUnavailableException("Unable to open approved dataset", exception);
    }
  }

  private String digest() throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(settings.path())) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record Verification(DatasetState state, DatasetReleaseMetadata metadata) {}
}
