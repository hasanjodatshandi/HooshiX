package com.sajtech.conversation.infrastructure.health;

import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import java.sql.SQLException;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

public final class ConversationReadinessHealthIndicator implements HealthIndicator {
  private final DataSource dataSource;
  private final FileBackedContentKeyRing keyRing;

  public ConversationReadinessHealthIndicator(
      DataSource dataSource, FileBackedContentKeyRing keyRing) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.keyRing = Objects.requireNonNull(keyRing);
  }

  @Override
  public Health health() {
    if (!keyRing.isFresh()) {
      return down("content_key_unavailable");
    }
    try (var connection = dataSource.getConnection();
        var statement =
            connection.prepareStatement("SELECT to_regclass('public.conversation') IS NOT NULL");
        var result = statement.executeQuery()) {
      if (!result.next() || !result.getBoolean(1)) {
        return down("schema_unavailable");
      }
      return Health.up().build();
    } catch (SQLException exception) {
      return down("database_unavailable");
    }
  }

  private static Health down(String reason) {
    return Health.down().withDetail("reason", reason).build();
  }
}
