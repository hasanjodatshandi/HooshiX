package com.sajtech.conversation.infrastructure.persistence;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.application.model.MessagePage;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.port.out.ModelRunRepository;
import com.sajtech.conversation.domain.ConversationMessage;
import com.sajtech.conversation.domain.MessageRole;
import com.sajtech.conversation.domain.ModelRun;
import com.sajtech.conversation.domain.ModelRunFailure;
import com.sajtech.conversation.domain.ModelRunState;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.content.ContentPurpose;
import com.sajtech.conversation.infrastructure.security.content.EncryptedContent;
import java.sql.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcModelRunRepository implements ModelRunRepository {
  private final DataSource dataSource;
  private final AesGcmContentCrypto crypto;

  public JdbcModelRunRepository(DataSource dataSource, AesGcmContentCrypto crypto) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.crypto = Objects.requireNonNull(crypto);
  }

  @Override
  public ModelRun findAcceptedReplay(
      ConversationActor actor, UUID requestId, UUID conversationId, String userMessage) {
    return transaction(
        actor, connection -> findReplay(connection, actor, requestId, conversationId, userMessage));
  }

  @Override
  public ModelRun accept(
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID messageId,
      UUID runId,
      String userMessage,
      ModelExecutionPolicy policy,
      Instant now) {
    return transaction(
        actor,
        connection -> {
          lockIdempotencyKey(connection, actor, requestId);
          ModelRun replay = findReplay(connection, actor, requestId, conversationId, userMessage);
          if (replay != null) return replay;
          lockActiveOwnedConversation(connection, actor, conversationId);
          reserveBudget(connection, actor, policy.maximumReservationMicroUsd(), now);
          long ordinal = nextOrdinal(connection, conversationId);
          EncryptedContent encrypted =
              crypto.encrypt(
                  actor.tenantId(),
                  conversationId,
                  messageId,
                  ContentPurpose.USER_MESSAGE,
                  userMessage);
          insertMessage(connection, actor, conversationId, messageId, encrypted, ordinal, now);
          insertRun(connection, actor, requestId, conversationId, messageId, runId, policy, now);
          touchConversation(connection, actor, conversationId, now);
          return new ModelRun(
              runId,
              conversationId,
              ModelRunState.QUEUED,
              policy.modelAlias(),
              policy.promptVersion(),
              policy.priceVersion(),
              policy.maximumReservationMicroUsd(),
              0,
              ModelRunFailure.NONE,
              false,
              now,
              null,
              null);
        });
  }

  @Override
  public MessagePage listMessagesOwned(
      ConversationActor actor, UUID conversationId, int pageSize, String pageToken) {
    final MessagePageToken.Cursor cursor;
    try {
      cursor = pageToken.isEmpty() ? null : MessagePageToken.decode(pageToken);
    } catch (IllegalArgumentException exception) {
      throw invalidRequest();
    }
    return transaction(
        actor,
        connection -> {
          requireOwnedConversation(connection, actor, conversationId);
          String cursorSql = cursor == null ? "" : " AND (ordinal, message_id) < (?, ?)";
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT message_id, tenant_id, conversation_id, role, content_key_id, content_nonce, "
                      + "content_ciphertext, ordinal, created_at FROM conversation_message "
                      + "WHERE conversation_id = ?"
                      + cursorSql
                      + " ORDER BY ordinal DESC, message_id DESC LIMIT ?")) {
            int index = 1;
            statement.setObject(index++, conversationId);
            if (cursor != null) {
              statement.setLong(index++, cursor.ordinal());
              statement.setObject(index++, cursor.id());
            }
            statement.setInt(index, pageSize + 1);
            var values = new ArrayList<ConversationMessage>(pageSize + 1);
            try (ResultSet rows = statement.executeQuery()) {
              while (rows.next()) values.add(mapMessage(rows));
            }
            String next = "";
            if (values.size() > pageSize) {
              while (values.size() > pageSize) values.remove(values.size() - 1);
              ConversationMessage last = values.get(values.size() - 1);
              next = MessagePageToken.encode(last.ordinal(), last.id());
            }
            return new MessagePage(values, next);
          }
        });
  }

  @Override
  public ModelRun getOwned(ConversationActor actor, UUID conversationId, UUID runId) {
    return transaction(
        actor, connection -> requireOwnedRun(connection, actor, conversationId, runId));
  }

  @Override
  public ModelRun cancelOwned(
      ConversationActor actor, UUID requestId, UUID conversationId, UUID runId, Instant now) {
    return transaction(
        actor,
        connection -> {
          lockCancellationIdempotencyKey(connection, actor, requestId);
          ModelRun replay = findCancelReplay(connection, actor, requestId, conversationId, runId);
          if (replay != null) return replay;
          ModelRun current = lockOwnedRun(connection, actor, conversationId, runId);
          if (current.state() == ModelRunState.QUEUED) {
            releaseBudget(connection, actor, current.reservedCostMicroUsd(), now);
            updateCancellation(connection, runId, true, now);
          } else if (current.state() == ModelRunState.RUNNING) {
            updateCancellation(connection, runId, false, now);
          }
          recordCancellationRequest(connection, actor, requestId, conversationId, runId, now);
          return requireOwnedRun(connection, actor, conversationId, runId);
        });
  }

  private ModelRun findReplay(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      String userMessage)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT r.run_id, r.tenant_id, r.conversation_id, r.state, r.model_alias, "
                + "r.prompt_version, r.price_version, r.reserved_cost_microunits, "
                + "r.actual_cost_microunits, r.failure_category, r.cancellation_requested, "
                + "r.created_at, r.claimed_at, r.completed_at, m.message_id, m.content_key_id, "
                + "m.content_nonce, m.content_ciphertext FROM conversation_model_run r "
                + "JOIN conversation_message m ON m.message_id = r.user_message_id "
                + "WHERE r.requester_membership_id = ? AND r.request_id = ?")) {
      statement.setObject(1, actor.membershipId());
      statement.setObject(2, requestId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        UUID storedConversation = row.getObject("conversation_id", UUID.class);
        UUID messageId = row.getObject("message_id", UUID.class);
        String storedMessage =
            crypto.decrypt(
                actor.tenantId(),
                storedConversation,
                messageId,
                ContentPurpose.USER_MESSAGE,
                new EncryptedContent(
                    row.getString("content_key_id"),
                    row.getBytes("content_nonce"),
                    row.getBytes("content_ciphertext")));
        if (!conversationId.equals(storedConversation) || !userMessage.equals(storedMessage)) {
          throw conflict();
        }
        return mapRun(row);
      }
    }
  }

  private static void lockIdempotencyKey(
      Connection connection, ConversationActor actor, UUID requestId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, actor.tenantId() + ":run:" + actor.membershipId() + ":" + requestId);
      statement.executeQuery().close();
    }
  }

  private static void lockCancellationIdempotencyKey(
      Connection connection, ConversationActor actor, UUID requestId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(
          1, actor.tenantId() + ":run-cancel:" + actor.membershipId() + ":" + requestId);
      statement.executeQuery().close();
    }
  }

  private static void lockActiveOwnedConversation(
      Connection connection, ConversationActor actor, UUID conversationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT lifecycle FROM conversation WHERE conversation_id = ? "
                + "AND owner_membership_id = ? FOR UPDATE")) {
      statement.setObject(1, conversationId);
      statement.setObject(2, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw conversationNotFound();
        if (!"ACTIVE".equals(row.getString("lifecycle"))) throw invalidState();
      }
    }
  }

  private static void requireOwnedConversation(
      Connection connection, ConversationActor actor, UUID conversationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM conversation WHERE conversation_id = ? AND owner_membership_id = ? "
                + "AND lifecycle <> 'DELETED'")) {
      statement.setObject(1, conversationId);
      statement.setObject(2, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw conversationNotFound();
      }
    }
  }

  private static void reserveBudget(
      Connection connection, ConversationActor actor, long amount, Instant now)
      throws SQLException {
    updateBudget(connection, actor, "TENANT", actor.tenantId(), amount, now, true);
    updateBudget(connection, actor, "MEMBERSHIP", actor.membershipId(), amount, now, true);
  }

  private static void releaseBudget(
      Connection connection, ConversationActor actor, long amount, Instant now)
      throws SQLException {
    updateBudget(connection, actor, "TENANT", actor.tenantId(), amount, now, false);
    updateBudget(connection, actor, "MEMBERSHIP", actor.membershipId(), amount, now, false);
  }

  private static void updateBudget(
      Connection connection,
      ConversationActor actor,
      String scope,
      UUID scopeId,
      long amount,
      Instant now,
      boolean reserve)
      throws SQLException {
    try (PreparedStatement statement =
        reserve
            ? connection.prepareStatement(
                "UPDATE conversation_budget_account SET reserved_micro_usd = "
                    + "reserved_micro_usd + ?, version = version + 1, updated_at = ? "
                    + "WHERE tenant_id = ? AND scope_type = ? AND scope_id = ? AND "
                    + "limit_micro_usd - charged_micro_usd - reserved_micro_usd >= ?")
            : connection.prepareStatement(
                "UPDATE conversation_budget_account SET reserved_micro_usd = "
                    + "reserved_micro_usd - ?, version = version + 1, updated_at = ? "
                    + "WHERE tenant_id = ? AND scope_type = ? AND scope_id = ? "
                    + "AND reserved_micro_usd >= ?")) {
      statement.setLong(1, amount);
      statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(3, actor.tenantId());
      statement.setString(4, scope);
      statement.setObject(5, scopeId);
      statement.setLong(6, amount);
      if (statement.executeUpdate() != 1) throw budgetUnavailable();
    }
  }

  private static long nextOrdinal(Connection connection, UUID conversationId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM conversation_message WHERE conversation_id = ?")) {
      statement.setObject(1, conversationId);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private static void insertMessage(
      Connection connection,
      ConversationActor actor,
      UUID conversationId,
      UUID messageId,
      EncryptedContent encrypted,
      long ordinal,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_message (message_id, tenant_id, conversation_id, "
                + "author_membership_id, role, content_key_id, content_nonce, content_ciphertext, "
                + "ordinal, created_at) VALUES (?, ?, ?, ?, 'USER', ?, ?, ?, ?, ?)")) {
      statement.setObject(1, messageId);
      statement.setObject(2, actor.tenantId());
      statement.setObject(3, conversationId);
      statement.setObject(4, actor.membershipId());
      statement.setString(5, encrypted.keyId());
      statement.setBytes(6, encrypted.nonce());
      statement.setBytes(7, encrypted.ciphertext());
      statement.setLong(8, ordinal);
      statement.setObject(9, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  private static void insertRun(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID messageId,
      UUID runId,
      ModelExecutionPolicy policy,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_model_run (run_id, tenant_id, conversation_id, "
                + "requester_membership_id, request_id, user_message_id, state, model_alias, "
                + "prompt_version, price_version, reserved_cost_microunits, actual_cost_microunits, "
                + "cancellation_requested, created_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, 0, false, ?)")) {
      statement.setObject(1, runId);
      statement.setObject(2, actor.tenantId());
      statement.setObject(3, conversationId);
      statement.setObject(4, actor.membershipId());
      statement.setObject(5, requestId);
      statement.setObject(6, messageId);
      statement.setString(7, policy.modelAlias());
      statement.setString(8, policy.promptVersion());
      statement.setString(9, policy.priceVersion());
      statement.setLong(10, policy.maximumReservationMicroUsd());
      statement.setObject(11, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  private static void touchConversation(
      Connection connection, ConversationActor actor, UUID conversationId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation SET aggregate_version = aggregate_version + 1, last_activity_at = ? "
                + "WHERE conversation_id = ? AND owner_membership_id = ? AND lifecycle = 'ACTIVE'")) {
      statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(2, conversationId);
      statement.setObject(3, actor.membershipId());
      if (statement.executeUpdate() != 1) throw conflict();
    }
  }

  private ModelRun findCancelReplay(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID runId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            runSelect()
                + " JOIN conversation_run_mutation_request m ON m.run_id = r.run_id "
                + "WHERE m.requester_membership_id = ? AND m.request_id = ? "
                + "AND c.lifecycle <> 'DELETED'")) {
      statement.setObject(1, actor.membershipId());
      statement.setObject(2, requestId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        if (!conversationId.equals(row.getObject("conversation_id", UUID.class))
            || !runId.equals(row.getObject("run_id", UUID.class))) throw conflict();
        return mapRun(row);
      }
    }
  }

  private ModelRun lockOwnedRun(
      Connection connection, ConversationActor actor, UUID conversationId, UUID runId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            runSelect()
                + " WHERE r.run_id = ? AND r.conversation_id = ? AND r.requester_membership_id = ? "
                + "AND c.lifecycle <> 'DELETED' FOR UPDATE OF r")) {
      statement.setObject(1, runId);
      statement.setObject(2, conversationId);
      statement.setObject(3, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw runNotFound();
        return mapRun(row);
      }
    }
  }

  private ModelRun requireOwnedRun(
      Connection connection, ConversationActor actor, UUID conversationId, UUID runId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            runSelect()
                + " WHERE r.run_id = ? AND r.conversation_id = ? AND r.requester_membership_id = ? "
                + "AND c.lifecycle <> 'DELETED'")) {
      statement.setObject(1, runId);
      statement.setObject(2, conversationId);
      statement.setObject(3, actor.membershipId());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw runNotFound();
        return mapRun(row);
      }
    }
  }

  private static String runSelect() {
    return "SELECT r.run_id, r.conversation_id, r.state, r.model_alias, r.prompt_version, "
        + "r.price_version, r.reserved_cost_microunits, r.actual_cost_microunits, "
        + "r.failure_category, r.cancellation_requested, r.created_at, r.claimed_at, r.completed_at "
        + "FROM conversation_model_run r JOIN conversation c "
        + "ON c.conversation_id = r.conversation_id AND c.tenant_id = r.tenant_id";
  }

  private static void updateCancellation(
      Connection connection, UUID runId, boolean queued, Instant now) throws SQLException {
    String sql =
        queued
            ? "UPDATE conversation_model_run SET state = 'CANCELED', cancellation_requested = true, "
                + "completed_at = ? WHERE run_id = ? AND state = 'QUEUED'"
            : "UPDATE conversation_model_run SET cancellation_requested = true "
                + "WHERE run_id = ? AND state = 'RUNNING'";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      if (queued) {
        statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        statement.setObject(2, runId);
      } else {
        statement.setObject(1, runId);
      }
      if (statement.executeUpdate() != 1) throw conflict();
    }
  }

  private static void recordCancellationRequest(
      Connection connection,
      ConversationActor actor,
      UUID requestId,
      UUID conversationId,
      UUID runId,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_run_mutation_request (tenant_id, requester_membership_id, "
                + "request_id, conversation_id, run_id, operation, created_at) "
                + "VALUES (?, ?, ?, ?, ?, 'CANCEL', ?)")) {
      statement.setObject(1, actor.tenantId());
      statement.setObject(2, actor.membershipId());
      statement.setObject(3, requestId);
      statement.setObject(4, conversationId);
      statement.setObject(5, runId);
      statement.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.executeUpdate();
    }
  }

  private ConversationMessage mapMessage(ResultSet row) throws SQLException {
    UUID id = row.getObject("message_id", UUID.class);
    UUID tenant = row.getObject("tenant_id", UUID.class);
    UUID conversation = row.getObject("conversation_id", UUID.class);
    MessageRole role = MessageRole.valueOf(row.getString("role"));
    ContentPurpose purpose =
        role == MessageRole.USER ? ContentPurpose.USER_MESSAGE : ContentPurpose.ASSISTANT_MESSAGE;
    String content =
        crypto.decrypt(
            tenant,
            conversation,
            id,
            purpose,
            new EncryptedContent(
                row.getString("content_key_id"),
                row.getBytes("content_nonce"),
                row.getBytes("content_ciphertext")));
    return new ConversationMessage(
        id,
        conversation,
        role,
        content,
        row.getLong("ordinal"),
        row.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static ModelRun mapRun(ResultSet row) throws SQLException {
    String failure = row.getString("failure_category");
    OffsetDateTime started = row.getObject("claimed_at", OffsetDateTime.class);
    OffsetDateTime completed = row.getObject("completed_at", OffsetDateTime.class);
    return new ModelRun(
        row.getObject("run_id", UUID.class),
        row.getObject("conversation_id", UUID.class),
        ModelRunState.valueOf(row.getString("state")),
        row.getString("model_alias"),
        row.getString("prompt_version"),
        row.getString("price_version"),
        row.getLong("reserved_cost_microunits"),
        row.getLong("actual_cost_microunits"),
        failure == null ? ModelRunFailure.NONE : ModelRunFailure.valueOf(failure),
        row.getBoolean("cancellation_requested"),
        row.getObject("created_at", OffsetDateTime.class).toInstant(),
        started == null ? null : started.toInstant(),
        completed == null ? null : completed.toInstant());
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
      throw persistenceUnavailable(exception);
    } catch (RuntimeException exception) {
      throw persistenceUnavailable(exception);
    }
  }

  private static ConversationException invalidRequest() {
    return new ConversationException(
        ConversationError.INVALID_REQUEST, "Message page token is invalid");
  }

  private static ConversationException conversationNotFound() {
    return new ConversationException(
        ConversationError.CONVERSATION_NOT_FOUND, "Conversation was not found");
  }

  private static ConversationException runNotFound() {
    return new ConversationException(
        ConversationError.MODEL_RUN_NOT_FOUND, "Model run was not found");
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

  private static ConversationException budgetUnavailable() {
    return new ConversationException(
        ConversationError.BUDGET_UNAVAILABLE, "Conversation budget is unavailable");
  }

  private static ConversationException persistenceUnavailable(RuntimeException exception) {
    return new ConversationException(
        ConversationError.PERSISTENCE_UNAVAILABLE,
        "Conversation persistence is unavailable",
        exception);
  }

  private static ConversationException persistenceUnavailable(SQLException exception) {
    return new ConversationException(
        ConversationError.PERSISTENCE_UNAVAILABLE,
        "Conversation persistence is unavailable",
        exception);
  }

  @FunctionalInterface
  private interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
