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
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcConversationRepository implements ConversationRepository {
  private final DataSource dataSource;
  private final AesGcmContentCrypto crypto;

  public JdbcConversationRepository(DataSource dataSource, AesGcmContentCrypto crypto) {
    this.dataSource = dataSource;
    this.crypto = crypto;
  }

  @Override
  public Conversation create(
      ConversationActor actor, UUID requestId, UUID conversationId, String title, Instant now) {
    return transaction(
        actor,
        connection -> {
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
                  "INSERT INTO conversation (conversation_id, tenant_id, owner_membership_id, "
                      + "create_request_id, title_key_id, title_nonce, title_ciphertext, lifecycle, "
                      + "aggregate_version, created_at, last_activity_at) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?)")) {
            statement.setObject(1, conversationId);
            statement.setObject(2, actor.tenantId());
            statement.setObject(3, actor.membershipId());
            statement.setObject(4, requestId);
            statement.setString(5, encrypted.keyId());
            statement.setBytes(6, encrypted.nonce());
            statement.setBytes(7, encrypted.ciphertext());
            statement.setObject(8, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
            statement.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
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

  @Override
  public ConversationPage listOwned(ConversationActor actor, int pageSize, String pageToken) {
    return transaction(
        actor,
        connection -> {
          ConversationPageToken.Cursor cursor =
              pageToken.isEmpty() ? null : ConversationPageToken.decode(pageToken);
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
          Conversation current = requireOwned(connection, actor, conversationId, false);
          if (current.lifecycle() == ConversationLifecycle.ARCHIVED) return current;
          if (current.lifecycle() != ConversationLifecycle.ACTIVE
              || current.version() != expectedVersion) {
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
          Conversation current = requireOwned(connection, actor, conversationId, true);
          if (current.lifecycle() == ConversationLifecycle.DELETED) return null;
          if (current.version() != expectedVersion) throw conflict();
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
          return null;
        });
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
          limits.execute("SET LOCAL lock_timeout = '200ms'");
          limits.execute("SET LOCAL statement_timeout = '800ms'");
        }
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (ConversationException exception) {
      throw exception;
    } catch (IllegalArgumentException exception) {
      throw new ConversationException(
          ConversationError.INVALID_REQUEST, "Conversation request is invalid", exception);
    } catch (SQLException exception) {
      if ("23505".equals(exception.getSQLState())) throw conflict();
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

  @FunctionalInterface
  private interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
