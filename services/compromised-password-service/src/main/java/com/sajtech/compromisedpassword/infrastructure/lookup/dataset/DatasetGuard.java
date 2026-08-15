package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
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
    private final DatasetSettings settings;
    private final Clock clock;
    private final DatasetState staticState;

    public DatasetGuard(DatasetSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.staticState = verifyStaticState();
    }

    public DatasetState state() {
        if (staticState != DatasetState.READY) {
            return staticState;
        }
        Instant now = clock.instant();
        if (settings.acquiredAt().isAfter(now)) {
            return DatasetState.INCOMPATIBLE;
        }
        if (Duration.between(settings.acquiredAt(), now).compareTo(MAX_DATASET_AGE) > 0) {
            return DatasetState.STALE;
        }
        return DatasetState.READY;
    }

    public Connection openReadOnlyConnection() {
        DatasetState current = state();
        if (current != DatasetState.READY) {
            throw new DatasetUnavailableException("Dataset is not ready: " + current.name());
        }
        return openConnection();
    }

    private DatasetState verifyStaticState() {
        if (!Files.isRegularFile(settings.path())) {
            return DatasetState.MISSING;
        }
        try {
            if (!digest().equals(settings.expectedSha256())) {
                return DatasetState.CORRUPT;
            }
            try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                        return DatasetState.CORRUPT;
                    }
                }
                try (ResultSet schemaCheck =
                        statement.executeQuery("SELECT 1 FROM compromised_password LIMIT 1")) {
                    if (schemaCheck.next() && schemaCheck.getInt(1) != 1) {
                        return DatasetState.INCOMPATIBLE;
                    }
                }
            }
            return DatasetState.READY;
        } catch (IOException | SQLException | RuntimeException exception) {
            return DatasetState.UNAVAILABLE;
        }
    }

    private Connection openConnection() {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.enableLoadExtension(false);
        config.setSharedCache(false);
        String jdbcUrl = "jdbc:sqlite:file:" + settings.path() + "?immutable=1&mode=ro";
        try {
            Connection connection = config.createConnection(jdbcUrl);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
            }
            return connection;
        } catch (SQLException exception) {
            throw new DatasetUnavailableException("Unable to open approved dataset", exception);
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
}
