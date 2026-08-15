package com.sajtech.compromisedpassword.infrastructure.lookup.dataset;

import com.sajtech.compromisedpassword.application.lookup.port.out.CompromisedPasswordRepository;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SqliteCompromisedPasswordRepository implements CompromisedPasswordRepository {
    private static final String LOOKUP_SQL =
            "SELECT substr(hex(hash), 6) AS suffix, occurrence_count "
                    + "FROM compromised_password WHERE prefix = ? ORDER BY hash";

    private final DatasetGuard datasetGuard;
    private final int maxPrefixCardinality;

    public SqliteCompromisedPasswordRepository(DatasetGuard datasetGuard, int maxPrefixCardinality) {
        this.datasetGuard = Objects.requireNonNull(datasetGuard, "datasetGuard");
        if (maxPrefixCardinality <= 0) {
            throw new IllegalArgumentException("Maximum prefix cardinality must be positive");
        }
        this.maxPrefixCardinality = maxPrefixCardinality;
    }

    @Override
    public List<CompromisedHashMatch> findByPrefix(Sha1Prefix prefix) {
        Objects.requireNonNull(prefix, "prefix");
        try (Connection connection = datasetGuard.openReadOnlyConnection();
                PreparedStatement statement = connection.prepareStatement(LOOKUP_SQL)) {
            statement.setInt(1, prefix.asInteger());
            try (ResultSet results = statement.executeQuery()) {
                List<CompromisedHashMatch> matches = new ArrayList<>();
                while (results.next()) {
                    if (matches.size() >= maxPrefixCardinality) {
                        throw new DatasetUnavailableException("Dataset exceeds approved response bound");
                    }
                    matches.add(
                            new CompromisedHashMatch(
                                    results.getString("suffix"), results.getLong("occurrence_count")));
                }
                return List.copyOf(matches);
            }
        } catch (SQLException exception) {
            throw new DatasetUnavailableException("Dataset lookup failed", exception);
        }
    }
}
