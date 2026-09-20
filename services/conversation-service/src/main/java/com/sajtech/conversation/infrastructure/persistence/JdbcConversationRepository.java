package com.sajtech.conversation.infrastructure.persistence;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.ConversationPage;
import com.sajtech.conversation.application.port.out.ConversationRepository;
import com.sajtech.conversation.domain.Conversation;
import com.sajtech.conversation.domain.ConversationLifecycle;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.content.ContentPurpose;
import com.sajtech.conversation.infrastructure.security.content.EncryptedContent;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcConversationRepository implements ConversationRepository {
  private final DataSource dataSource;
  private final AesGcmContentCrypto crypto;

  public JdbcConversationRepository(DataSource dataSource, AesGcmContentCrypto crypto) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.crypto = Objects.requireNonNull(crypto);
  }

  @Override
  public Conversation create(
      ConversationActor actor, UUID requestId, UUID conversationId, String title, Instant now) {
    return transaction(
        actor,
        connection -> {
          lockIdempotencyKey(connection, actor, requestId);
          Conversation existing = findByCreateRequest(connection, actor, requestId);
          if (existing != null) {
            if (!existing.title().equals(title)) throw conflict();
            return existing;
          }
          EncryptedContent encrypted =
              crypto.encrypt(
                  actor.tenantId(), conversationId, conversationId, ContentPurpose.TITLE, title);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO conversation (conversation_id, tenant_id, owner_membership_id, owner_user_id, "
                      + "create_request_id, title_key_id, title_nonce, title_ciphertext, lifecycle, "
                      + "aggregate_version, created_at, last_activity_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)")) {
            statement.setObject(1, conversationId);
            statement.setObject(2, actor.tenantId());
            statement.setObject(3, actor.membershipId());
            statement.setObject(4, actor.userId());
            statement.setObject(5, requestId);
            statement.setString(6, encrypted.keyId());
            statement.setBytes(7, encrypted.nonce());
            statement.setBytes(8, encrypted.ciphertext());
            statement.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(10, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO conversation_subject_index(user_id,tenant_id,conversation_id) VALUES (?,?,?)")) {
            statement.setObject(1, actor.userId());
            statement.setObject(2, actor.tenantId());
            statement.setObject(3, conversationId);
            statement.executeUpdate();
          }
          return new Conversation(
              conversationId,
              actor.tenantId(),
              actor.membershipId(),
              title,
              ConversationLifecycle.ACTIVE,
              1,
              now,
              now);
        });
  }

  private void lockIdempotencyKey(Connection connection, ConversationActor actor, UUID requestId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, actor.tenantId() + ":" + actor.membershipId() + ":" + requestId);
      statement.executeQuery().close();
    }
  }

  @Override
  public ConversationPage listOwned(ConversationActor actor, int pageSize, String pageToken) {
    final ConversationPageToken.Cursor cursor;
    try {
      cursor = pageToken.isEmpty() ? null : ConversationPageToken.decode(pageToken);
    } catch (IllegalArgumentException exception) {
      throw new ConversationException(
          ConversationError.INVALID_REQUEST, "Conversation page token is invalid");
    }
    return transaction(
        actor,
        connection -> {
          String cursorSql =
              cursor == null ? "" : " AND (last_activity_at, conversation_id) < (?, ?)";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT conversation_id, tenant_id, owner_membership_id, title_key_id, title_nonce, "
                      + "title_ciphertext, lifecycle, aggregate_version, created_at, last_activity_at "
                      + "FROM conversation WHERE owner_membership_id = ? AND lifecycle <> 'DELETED'"
                      + cursorSql
                      + " ORDER BY last_activity_at DESC, conversation_id DESC LIMIT ?")) {
            int index = 1;
            statement.setObject(index++, actor.membershipId());
            if (cursor != null) {
              statement.setObject(
                  index++, OffsetDateTime.ofInstant(cursor.activity(), ZoneOffset.UTC));
              statement.setObject(index++, cursor.id());
            }
            statement.setInt(index, pageSize + 1);
            var values = new ArrayList<Conversation>(pageSize + 1);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) values.add(map(rows));
            }
            String next = "";
            if (values.size() > pageSize) {
              while (values.size() > pageSize) values.remove(values.size() - 1);
              Conversation last = values.get(values.size() - 1);
              next = ConversationPageToken.encode(last.lastActivityAt(), last.id());
            }
            return new ConversationPage(values, next);
          }
        });
  }

  @Override
  public Conversation getOwned(ConversationActor actor, UUID conversationId) {
    return transaction(actor, connection -> requireOwned(connection, actor, conversationId, false));
  }

  @Override
  public Conversation archiveOwned(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      long expectedVersion,
      Instant now) {
    return transaction(
        actor,
        connection -> {
          if (mutationReplay(
              connection, actor, requestId, conversationId, "ARCHIVE", expectedVersion)) {
            return requireOwned(connection, actor, conversationId, false);
          }
          Conversation current = requireOwned(connection, actor, conversationId, false);
          if (current.lifecycle() != ConversationLifecycle.ACTIVE) throw invalidState();
          if (current.version() != expectedVersion) {
            throw conflict();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation SET lifecycle = 'ARCHIVED', aggregate_version = aggregate_version + 1, "
                      + "last_activity_at = ? WHERE conversation_id = ? AND owner_membership_id = ? "
                      + "AND lifecycle = 'ACTIVE' AND aggregate_version = ?")) {
            statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(2, conversationId);
            statement.setObject(3, actor.membershipId());
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) throw conflict();
          }
          recordMutation(
              connection, actor, requestId, conversationId, "ARCHIVE", expectedVersion, now);
          return new Conversation(
              current.id(),
              current.tenantId(),
              current.ownerMembershipId(),
              current.title(),
              ConversationLifecycle.ARCHIVED,
              current.version() + 1,
              current.createdAt(),
              now);
        });
  }

  @Override
  public void deleteOwned(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      long expectedVersion,
      Instant now) {
    transaction(
        actor,
        connection -> {
          if (mutationReplay(
              connection, actor, requestId, conversationId, "DELETE", expectedVersion)) {
            return null;
          }
          Conversation current = requireOwned(connection, actor, conversationId, true);
          if (current.lifecycle() == ConversationLifecycle.DELETED) throw invalidState();
          if (current.version() != expectedVersion) throw conflict();
          releaseQueuedRunBudgetAndEraseMessages(connection, actor, conversationId, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation SET lifecycle = 'DELETED', aggregate_version = aggregate_version + 1, "
                      + "last_activity_at = ?, title_key_id = 'deleted', title_nonce = decode('000000000000000000000000', 'hex'), "
                      + "title_ciphertext = decode('00000000000000000000000000000000', 'hex') "
                      + "WHERE conversation_id = ? AND owner_membership_id = ? AND aggregate_version = ?")) {
            statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(2, conversationId);
            statement.setObject(3, actor.membershipId());
            statement.setLong(4, expectedVersion);
            if (statement.executeUpdate() != 1) throw conflict();
          }
          recordMutation(
              connection, actor, requestId, conversationId, "DELETE", expectedVersion, now);
          return null;
        });
  }

  private static void releaseQueuedRunBudgetAndEraseMessages(
      Connection connection, ConversationActor actor, UUID conversationId, Instant now)
      throws SQLException {
    long queuedReservation = queuedReservation(connection, actor, conversationId);
    if (queuedReservation > 0) {
      releaseBudget(connection, actor, "TENANT", actor.tenantId(), queuedReservation, now);
      releaseBudget(connection, actor, "MEMBERSHIP", actor.membershipId(), queuedReservation, now);
    }
    try (PreparedStatement removeQueuedWork =
        connection.prepareStatement(
            "DELETE FROM conversation_model_run_queue WHERE conversation_id = ? "
                + "AND run_id IN (SELECT run_id FROM conversation_model_run "
                + "WHERE conversation_id = ? AND requester_membership_id = ? AND state = 'QUEUED')")) {
      removeQueuedWork.setObject(1, conversationId);
      removeQueuedWork.setObject(2, conversationId);
      removeQueuedWork.setObject(3, actor.membershipId());
      removeQueuedWork.executeUpdate();
    }
    try (PreparedStatement cancel =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET state = 'CANCELED', cancellation_requested = true, "
                + "completed_at = ? WHERE conversation_id = ? AND requester_membership_id = ? "
                + "AND state = 'QUEUED'")) {
      cancel.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      cancel.setObject(2, conversationId);
      cancel.setObject(3, actor.membershipId());
      cancel.executeUpdate();
    }
    try (PreparedStatement detach =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET user_message_id = NULL, "
                + "cancellation_requested = CASE WHEN state = 'RUNNING' THEN true "
                + "ELSE cancellation_requested END WHERE conversation_id = ? "
                + "AND requester_membership_id = ?")) {
      detach.setObject(1, conversationId);
      detach.setObject(2, actor.membershipId());
      detach.executeUpdate();
    }
    try (PreparedStatement erase =
        connection.prepareStatement("DELETE FROM conversation_message WHERE conversation_id = ?")) {
      erase.setObject(1, conversationId);
      erase.executeUpdate();
    }
  }

  private static long queuedReservation(
      Connection connection, ConversationActor actor, UUID conversationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(SUM(reserved_cost_microunits), 0) FROM conversation_model_run "
                + "WHERE conversation_id = ? AND requester_membership_id = ? AND state = 'QUEUED'")) {
      statement.setObject(1, conversationId);
      statement.setObject(2, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        try {
          return row.getObject(1, BigDecimal.class).longValueExact();
        } catch (ArithmeticException exception) {
          throw new ConversationException(
              ConversationError.PERSISTENCE_UNAVAILABLE,
              "Conversation persistence is unavailable",
              exception);
        }
      }
    }
  }

  private static void releaseBudget(
      Connection connection,
      ConversationActor actor,
      String scope,
      UUID scopeId,
      long amount,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_budget_account SET reserved_micro_usd = reserved_micro_usd - ?, "
                + "version = version + 1, updated_at = ? WHERE tenant_id = ? AND scope_type = ? "
                + "AND scope_id = ? AND reserved_micro_usd >= ?")) {
      statement.setLong(1, amount);
      statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(3, actor.tenantId());
      statement.setString(4, scope);
      statement.setObject(5, scopeId);
      statement.setLong(6, amount);
      if (statement.executeUpdate() != 1) {
        throw new ConversationException(
            ConversationError.PERSISTENCE_UNAVAILABLE, "Conversation persistence is unavailable");
      }
    }
  }

  private boolean mutationReplay(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      String operation,
      long expectedVersion)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT conversation_id, operation, expected_version FROM conversation_mutation_request "
                + "WHERE owner_membership_id = ? AND request_id = ?")) {
      statement.setObject(1, actor.membershipId());
      statement.setObject(2, requestId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return false;
        if (!conversationId.equals(row.getObject("conversation_id", UUID.class))
            || !operation.equals(row.getString("operation"))
            || expectedVersion != row.getLong("expected_version")) {
          throw conflict();
        }
        return true;
      }
    }
  }

  private void recordMutation(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      String operation,
      long expectedVersion,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_mutation_request (tenant_id, owner_membership_id, request_id, "
                + "conversation_id, operation, expected_version, resulting_version, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
      statement.setObject(1, actor.tenantId());
      statement.setObject(2, actor.membershipId());
      statement.setObject(3, requestId);
      statement.setObject(4, conversationId);
      statement.setString(5, operation);
      statement.setLong(6, expectedVersion);
      statement.setLong(7, expectedVersion + 1);
      statement.setObject(8, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  private Conversation findByCreateRequest(
      Connection connection, ConversationActor actor, UUID requestId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT conversation_id, tenant_id, owner_membership_id, title_key_id, title_nonce, "
                + "title_ciphertext, lifecycle, aggregate_version, created_at, last_activity_at "
                + "FROM conversation WHERE owner_membership_id = ? AND create_request_id = ?")) {
      statement.setObject(1, actor.membershipId());
      statement.setObject(2, requestId);
      try (ResultSet row = statement.executeQuery()) {
        return row.next() ? map(row) : null;
      }
    }
  }

  private Conversation requireOwned(
      Connection connection, ConversationActor actor, UUID id, boolean includeDeleted)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT conversation_id, tenant_id, owner_membership_id, title_key_id, title_nonce, "
                + "title_ciphertext, lifecycle, aggregate_version, created_at, last_activity_at "
                + "FROM conversation WHERE conversation_id = ? AND owner_membership_id = ?"
                + (includeDeleted ? "" : " AND lifecycle <> 'DELETED'"))) {
      statement.setObject(1, id);
      statement.setObject(2, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          throw new ConversationException(
              ConversationError.CONVERSATION_NOT_FOUND, "Conversation was not found");
        }
        return map(row);
      }
    }
  }

  private Conversation map(ResultSet row) throws SQLException {
    UUID id = row.getObject("conversation_id", UUID.class);
    UUID tenant = row.getObject("tenant_id", UUID.class);
    ConversationLifecycle lifecycle = ConversationLifecycle.valueOf(row.getString("lifecycle"));
    String title =
        lifecycle == ConversationLifecycle.DELETED
            ? "deleted"
            : crypto.decrypt(
                tenant,
                id,
                id,
                ContentPurpose.TITLE,
                new EncryptedContent(
                    row.getString("title_key_id"),
                    row.getBytes("title_nonce"),
                    row.getBytes("title_ciphertext")));
    return new Conversation(
        id,
        tenant,
        row.getObject("owner_membership_id", UUID.class),
        title,
        lifecycle,
        row.getLong("aggregate_version"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        row.getObject("last_activity_at", OffsetDateTime.class).toInstant());
  }

  private <T> T transaction(ConversationActor actor, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement context =
            connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
          context.setString(1, actor.tenantId().toString());
          context.executeQuery().close();
        }
        try (Statement limits = connection.createStatement()) {
          limits.execute("SET LOCAL lock_timeout = '100ms'");
          limits.execute("SET LOCAL statement_timeout = '500ms'");
        }
        TenantLifecycleProjection.requireActive(connection, actor.tenantId());
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ConversationException exception) {
      throw exception;
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw conflict();
      throw new ConversationException(
          ConversationError.PERSISTENCE_UNAVAILABLE,
          "Conversation persistence is unavailable",
          exception);
    } catch (RuntimeException exception) {
      throw new ConversationException(
          ConversationError.PERSISTENCE_UNAVAILABLE,
          "Conversation persistence is unavailable",
          exception);
    }
  }

  private static ConversationException conflict() {
    return new ConversationException(
        ConversationError.CONVERSATION_CONFLICT, "Conversation state conflicts with the request");
  }

  private static ConversationException invalidState() {
    return new ConversationException(
        ConversationError.CONVERSATION_INVALID_STATE,
        "Conversation lifecycle does not allow the operation");
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
