package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.io.IOException;
import java.io.InputStream;
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

    @TempDir Path tempDirectory;

    @Test
    void returnsCanonicalSuffixAndPositiveCountFromReadOnlyDataset() throws Exception {
        Path dataset = createDataset("ABCDE" + "1".repeat(35), 42L);
        DatasetSettings settings =
                new DatasetSettings(dataset, sha256(dataset), NOW.minusSeconds(3600), 1, 10);
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
                                    statement.executeUpdate(
                                            "DELETE FROM compromised_password WHERE prefix = 703710"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void staleDatasetFailsClosed() throws Exception {
        Path dataset = createDataset("ABCDE" + "2".repeat(35), 7L);
        DatasetSettings settings =
                new DatasetSettings(dataset, sha256(dataset), NOW.minusSeconds(36L * 86_400), 1, 10);
        DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));
        SqliteCompromisedPasswordRepository repository =
                new SqliteCompromisedPasswordRepository(guard, settings.maxPrefixCardinality());

        assertThat(guard.state()).isEqualTo(DatasetState.STALE);
        assertThatThrownBy(() -> repository.findByPrefix(Sha1Prefix.parse("ABCDE")))
                .isInstanceOf(DatasetUnavailableException.class);
    }

    @Test
    void responseBoundFailsClosedInsteadOfTruncating() throws Exception {
        Path dataset = createDataset("ABCDE" + "3".repeat(35), 1L);
        append(dataset, "ABCDE" + "4".repeat(35), 2L);
        DatasetSettings settings = new DatasetSettings(dataset, sha256(dataset), NOW, 1, 1);
        DatasetGuard guard = new DatasetGuard(settings, Clock.fixed(NOW, ZoneOffset.UTC));
        SqliteCompromisedPasswordRepository repository =
                new SqliteCompromisedPasswordRepository(guard, settings.maxPrefixCardinality());

        assertThatThrownBy(() -> repository.findByPrefix(Sha1Prefix.parse("ABCDE")))
                .isInstanceOf(DatasetUnavailableException.class)
                .hasMessageContaining("response bound");
    }

    private Path createDataset(String hash, long count) throws SQLException {
        Path path = tempDirectory.resolve("compromised-password.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE compromised_password ("
                            + "prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),"
                            + "hash BLOB NOT NULL CHECK (length(hash) = 20),"
                            + "occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),"
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

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                input.transferTo(new java.io.OutputStream() {
                    @Override
                    public void write(int value) {
                        digest.update((byte) value);
                    }

                    @Override
                    public void write(byte[] values, int offset, int length) {
                        digest.update(values, offset, length);
                    }
                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
