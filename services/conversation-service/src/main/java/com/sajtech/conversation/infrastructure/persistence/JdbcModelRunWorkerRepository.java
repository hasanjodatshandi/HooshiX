package com.sajtech.conversation.infrastructure.persistence;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ClaimedModelRun;
import com.sajtech.conversation.application.model.ModelExecutionPolicy;
import com.sajtech.conversation.application.model.ModelProviderMessage;
import com.sajtech.conversation.application.model.ModelProviderResult;
import com.sajtech.conversation.application.port.out.ModelRunWorkerRepository;
import com.sajtech.conversation.domain.MessageRole;
import com.sajtech.conversation.domain.ModelRunFailure;
import com.sajtech.conversation.domain.ModelRunState;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.content.ContentPurpose;
import com.sajtech.conversation.infrastructure.security.content.EncryptedContent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcModelRunWorkerRepository implements ModelRunWorkerRepository {
  private static final int MAXIMUM_CONTEXT_MESSAGES = 100;
  private final DataSource dataSource;
  private final AesGcmContentCrypto crypto;

  public JdbcModelRunWorkerRepository(DataSource dataSource, AesGcmContentCrypto crypto) {
    this.dataSource = Objects.requireNonNull(dataSource);
    this.crypto = Objects.requireNonNull(crypto);
  }

  @Override
  public Optional<ClaimedModelRun> claim(
      ModelExecutionPolicy policy,
      Instant now,
      Duration leaseDuration,
      int maximumConcurrentPerTenant) {
    Objects.requireNonNull(policy);
    Objects.requireNonNull(now);
    if (leaseDuration == null
        || leaseDuration.isZero()
        || leaseDuration.isNegative()
        || maximumConcurrentPerTenant < 1) {
      throw new IllegalArgumentException("Worker claim bounds are invalid");
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        setLimits(connection);
        QueueEntry entry = lockNextQueueEntry(connection, now, maximumConcurrentPerTenant);
        if (entry == null) {
          connection.commit();
          return Optional.empty();
        }
        if (!tryLockTenantClaimKey(connection, entry.tenantId())
            || !hasTenantCapacity(connection, entry.tenantId(), now, maximumConcurrentPerTenant)) {
          connection.commit();
          return Optional.empty();
        }
        setTenant(connection, entry.tenantId());
        QueuedRun run = lockRun(connection, entry);
        if (run == null || !"QUEUED".equals(run.state())) {
          deleteQueueEntry(connection, entry.runId());
          connection.commit();
          return Optional.empty();
        }
        if (!"ACTIVE".equals(run.lifecycle()) || !run.matches(policy)) {
          failQueuedRun(
              connection,
              entry,
              run,
              "ACTIVE".equals(run.lifecycle())
                  ? ModelRunFailure.EXECUTION_DISABLED
                  : ModelRunFailure.TENANT_INACTIVE,
              now);
          connection.commit();
          return Optional.empty();
        }
        List<ModelProviderMessage> messages =
            loadBoundedContext(connection, entry, run.userMessageId(), policy.maximumInputTokens());
        markRunning(connection, entry.runId(), now);
        leaseQueueEntry(connection, entry.runId(), now.plus(leaseDuration));
        connection.commit();
        return Optional.of(
            new ClaimedModelRun(
                entry.tenantId(),
                run.membershipId(),
                entry.conversationId(),
                entry.runId(),
                messages));
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw persistenceUnavailable(exception);
    }
  }

  @Override
  public void complete(
      ClaimedModelRun claim, ModelExecutionPolicy policy, ModelProviderResult result, Instant now) {
    Objects.requireNonNull(claim);
    Objects.requireNonNull(policy);
    Objects.requireNonNull(result);
    Objects.requireNonNull(now);
    Completion completion = completion(policy, result);
    tenantTransaction(
        claim.tenantId(),
        connection -> {
          LockedRun run = lockRunningRun(connection, claim, policy);
          boolean canceled = run.cancellationRequested();
          UUID assistantMessageId = null;
          ModelRunState state = completion.state();
          if (completion.state() == ModelRunState.SUCCEEDED && canceled) {
            state = ModelRunState.CANCELED;
          } else if (completion.state() == ModelRunState.SUCCEEDED) {
            assistantMessageId = UUID.randomUUID();
            insertAssistantMessage(
                connection,
                claim,
                assistantMessageId,
                result.output(),
                nextOrdinal(connection, claim));
          }
          reconcileBudget(
              connection, claim, run.reservedCostMicroUsd(), completion.actualCostMicroUsd(), now);
          updateTerminalRun(
              connection,
              claim.runId(),
              state,
              completion.failure(),
              completion.actualCostMicroUsd(),
              completion.recordUsage() ? result : null,
              now);
          deleteQueueEntry(connection, claim.runId());
          return null;
        });
  }

  @Override
  public int expireUnknown(ModelExecutionPolicy policy, Instant now, int maximumBatchSize) {
    Objects.requireNonNull(policy);
    Objects.requireNonNull(now);
    if (maximumBatchSize < 1 || maximumBatchSize > 100) {
      throw new IllegalArgumentException("Worker expiry batch is invalid");
    }
    List<QueueEntry> expired = expiredEntries(now, maximumBatchSize);
    int completed = 0;
    for (QueueEntry entry : expired) {
      boolean changed =
          tenantTransaction(
              entry.tenantId(), connection -> expireOne(connection, entry, policy, now));
      if (changed) completed++;
    }
    return completed;
  }

  private static QueueEntry lockNextQueueEntry(
      Connection connection, Instant now, int maximumConcurrentPerTenant) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT q.run_id, q.tenant_id, q.conversation_id FROM conversation_model_run_queue q "
                + "WHERE q.available_at <= ? AND q.claimed_until IS NULL "
                + "AND (SELECT count(*) FROM conversation_model_run_queue active "
                + "WHERE active.tenant_id = q.tenant_id AND active.claimed_until > ?) < ? "
                + "ORDER BY q.available_at, q.run_id FOR UPDATE OF q SKIP LOCKED LIMIT 1")) {
      OffsetDateTime timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
      statement.setObject(1, timestamp);
      statement.setObject(2, timestamp);
      statement.setInt(3, maximumConcurrentPerTenant);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        return new QueueEntry(
            row.getObject("run_id", UUID.class),
            row.getObject("tenant_id", UUID.class),
            row.getObject("conversation_id", UUID.class));
      }
    }
  }

  private static boolean tryLockTenantClaimKey(Connection connection, UUID tenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")) {
      statement.setString(1, "conversation-model-worker:" + tenantId);
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  private static boolean hasTenantCapacity(
      Connection connection, UUID tenantId, Instant now, int maximumConcurrentPerTenant)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) < ? FROM conversation_model_run_queue "
                + "WHERE tenant_id = ? AND claimed_until > ?")) {
      statement.setInt(1, maximumConcurrentPerTenant);
      statement.setObject(2, tenantId);
      statement.setObject(3, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getBoolean(1);
      }
    }
  }

  private static QueuedRun lockRun(Connection connection, QueueEntry entry) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT r.requester_membership_id, r.state, r.model_alias, r.prompt_version, "
                + "r.price_version, r.reserved_cost_microunits, r.user_message_id, c.lifecycle "
                + "FROM conversation_model_run r "
                + "JOIN conversation c ON c.conversation_id = r.conversation_id "
                + "AND c.tenant_id = r.tenant_id WHERE r.run_id = ? AND r.conversation_id = ? "
                + "FOR UPDATE OF r")) {
      statement.setObject(1, entry.runId());
      statement.setObject(2, entry.conversationId());
      try (ResultSet row = statement.executeQuery()) {
        return row.next()
            ? new QueuedRun(
                row.getObject("requester_membership_id", UUID.class),
                row.getString("state"),
                row.getString("model_alias"),
                row.getString("prompt_version"),
                row.getString("price_version"),
                row.getLong("reserved_cost_microunits"),
                row.getObject("user_message_id", UUID.class),
                row.getString("lifecycle"))
            : null;
      }
    }
  }

  private static void failQueuedRun(
      Connection connection, QueueEntry entry, QueuedRun run, ModelRunFailure failure, Instant now)
      throws SQLException {
    releaseQueuedBudget(
        connection, entry.tenantId(), "TENANT", entry.tenantId(), run.reservedCostMicroUsd(), now);
    releaseQueuedBudget(
        connection,
        entry.tenantId(),
        "MEMBERSHIP",
        run.membershipId(),
        run.reservedCostMicroUsd(),
        now);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET state = 'FAILED', failure_category = ?, "
                + "actual_cost_microunits = 0, completed_at = ? "
                + "WHERE run_id = ? AND state = 'QUEUED'")) {
      statement.setString(1, failure.name());
      statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(3, entry.runId());
      if (statement.executeUpdate() != 1) throw new IllegalStateException("Run rejection conflict");
    }
    deleteQueueEntry(connection, entry.runId());
  }

  private static void releaseQueuedBudget(
      Connection connection, UUID tenantId, String scope, UUID scopeId, long reserved, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_budget_account SET reserved_micro_usd = reserved_micro_usd - ?, "
                + "version = version + 1, updated_at = ? WHERE tenant_id = ? "
                + "AND scope_type = ? AND scope_id = ? AND reserved_micro_usd >= ?")) {
      statement.setLong(1, reserved);
      statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(3, tenantId);
      statement.setString(4, scope);
      statement.setObject(5, scopeId);
      statement.setLong(6, reserved);
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("Budget release conflict");
    }
  }

  private List<ModelProviderMessage> loadBoundedContext(
      Connection connection, QueueEntry entry, UUID userMessageId, int maximumInputTokens)
      throws SQLException {
    int maximumUtf8Bytes = Math.max(1, Math.multiplyExact(maximumInputTokens, 3) / 4);
    List<ModelProviderMessage> newestFirst = new ArrayList<>();
    int totalBytes = 0;
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT message_id, role, content_key_id, content_nonce, content_ciphertext "
                + "FROM conversation_message WHERE conversation_id = ? AND ordinal <= "
                + "(SELECT ordinal FROM conversation_message WHERE message_id = ?) "
                + "ORDER BY ordinal DESC, message_id DESC LIMIT ?")) {
      statement.setObject(1, entry.conversationId());
      statement.setObject(2, userMessageId);
      statement.setInt(3, MAXIMUM_CONTEXT_MESSAGES);
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          UUID messageId = rows.getObject("message_id", UUID.class);
          MessageRole role = MessageRole.valueOf(rows.getString("role"));
          String content =
              crypto.decrypt(
                  entry.tenantId(),
                  entry.conversationId(),
                  messageId,
                  role == MessageRole.USER
                      ? ContentPurpose.USER_MESSAGE
                      : ContentPurpose.ASSISTANT_MESSAGE,
                  new EncryptedContent(
                      rows.getString("content_key_id"),
                      rows.getBytes("content_nonce"),
                      rows.getBytes("content_ciphertext")));
          int bytes = content.getBytes(StandardCharsets.UTF_8).length;
          if (bytes > maximumUtf8Bytes && newestFirst.isEmpty()) {
            newestFirst.add(new ModelProviderMessage(role, content));
            break;
          }
          if (Math.addExact(totalBytes, bytes) > maximumUtf8Bytes) break;
          newestFirst.add(new ModelProviderMessage(role, content));
          totalBytes += bytes;
        }
      }
    }
    if (newestFirst.isEmpty()) throw new IllegalStateException("Model context is unavailable");
    Collections.reverse(newestFirst);
    return List.copyOf(newestFirst);
  }

  private static void markRunning(Connection connection, UUID runId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET state = 'RUNNING', claimed_at = ? "
                + "WHERE run_id = ? AND state = 'QUEUED'")) {
      statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(2, runId);
      if (statement.executeUpdate() != 1) throw new IllegalStateException("Run claim conflict");
    }
  }

  private static void leaseQueueEntry(Connection connection, UUID runId, Instant until)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run_queue SET claimed_until = ? WHERE run_id = ?")) {
      statement.setObject(1, OffsetDateTime.ofInstant(until, ZoneOffset.UTC));
      statement.setObject(2, runId);
      if (statement.executeUpdate() != 1) throw new IllegalStateException("Run lease conflict");
    }
  }

  private static LockedRun lockRunningRun(
      Connection connection, ClaimedModelRun claim, ModelExecutionPolicy policy)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT reserved_cost_microunits, cancellation_requested "
                + "FROM conversation_model_run WHERE run_id = ? AND conversation_id = ? "
                + "AND requester_membership_id = ? AND state = 'RUNNING' AND model_alias = ? "
                + "AND prompt_version = ? AND price_version = ? FOR UPDATE")) {
      statement.setObject(1, claim.runId());
      statement.setObject(2, claim.conversationId());
      statement.setObject(3, claim.membershipId());
      statement.setString(4, policy.modelAlias());
      statement.setString(5, policy.promptVersion());
      statement.setString(6, policy.priceVersion());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) throw new IllegalStateException("Run completion conflict");
        return new LockedRun(row.getLong(1), row.getBoolean(2));
      }
    }
  }

  private static long nextOrdinal(Connection connection, ClaimedModelRun claim)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM conversation_message "
                + "WHERE conversation_id = ?")) {
      statement.setObject(1, claim.conversationId());
      try (ResultSet row = statement.executeQuery()) {
        row.next();
        return row.getLong(1);
      }
    }
  }

  private void insertAssistantMessage(
      Connection connection, ClaimedModelRun claim, UUID messageId, String output, long ordinal)
      throws SQLException {
    EncryptedContent encrypted =
        crypto.encrypt(
            claim.tenantId(),
            claim.conversationId(),
            messageId,
            ContentPurpose.ASSISTANT_MESSAGE,
            output);
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_message (message_id, tenant_id, conversation_id, model_run_id, "
                + "author_membership_id, role, content_key_id, content_nonce, content_ciphertext, "
                + "ordinal, created_at) VALUES (?, ?, ?, ?, NULL, 'ASSISTANT', ?, ?, ?, ?, now())")) {
      statement.setObject(1, messageId);
      statement.setObject(2, claim.tenantId());
      statement.setObject(3, claim.conversationId());
      statement.setObject(4, claim.runId());
      statement.setString(5, encrypted.keyId());
      statement.setBytes(6, encrypted.nonce());
      statement.setBytes(7, encrypted.ciphertext());
      statement.setLong(8, ordinal);
      statement.executeUpdate();
    }
  }

  private static void reconcileBudget(
      Connection connection, ClaimedModelRun claim, long reserved, long actual, Instant now)
      throws SQLException {
    updateBudget(connection, claim, "TENANT", claim.tenantId(), reserved, actual, now);
    updateBudget(connection, claim, "MEMBERSHIP", claim.membershipId(), reserved, actual, now);
  }

  private static void updateBudget(
      Connection connection,
      ClaimedModelRun claim,
      String scope,
      UUID scopeId,
      long reserved,
      long actual,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_budget_account SET reserved_micro_usd = reserved_micro_usd - ?, "
                + "charged_micro_usd = charged_micro_usd + ?, version = version + 1, updated_at = ? "
                + "WHERE tenant_id = ? AND scope_type = ? AND scope_id = ? "
                + "AND reserved_micro_usd >= ? AND charged_micro_usd + ? <= limit_micro_usd")) {
      statement.setLong(1, reserved);
      statement.setLong(2, actual);
      statement.setObject(3, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(4, claim.tenantId());
      statement.setString(5, scope);
      statement.setObject(6, scopeId);
      statement.setLong(7, reserved);
      statement.setLong(8, actual);
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("Budget reconciliation conflict");
    }
  }

  private static void updateTerminalRun(
      Connection connection,
      UUID runId,
      ModelRunState state,
      ModelRunFailure failure,
      long actualCost,
      ModelProviderResult usage,
      Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET state = ?, failure_category = ?, "
                + "actual_cost_microunits = ?, input_tokens = ?, output_tokens = ?, "
                + "completed_at = ? WHERE run_id = ? AND state = 'RUNNING'")) {
      statement.setString(1, state.name());
      if (failure == ModelRunFailure.NONE) statement.setNull(2, java.sql.Types.VARCHAR);
      else statement.setString(2, failure.name());
      statement.setLong(3, actualCost);
      if (usage == null) {
        statement.setNull(4, java.sql.Types.INTEGER);
        statement.setNull(5, java.sql.Types.INTEGER);
      } else {
        statement.setInt(4, usage.inputTokens());
        statement.setInt(5, usage.outputTokens());
      }
      statement.setObject(6, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setObject(7, runId);
      if (statement.executeUpdate() != 1)
        throw new IllegalStateException("Run completion conflict");
    }
  }

  private List<QueueEntry> expiredEntries(Instant now, int maximumBatchSize) {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT run_id, tenant_id, conversation_id FROM conversation_model_run_queue "
                    + "WHERE claimed_until <= ? ORDER BY claimed_until, run_id LIMIT ?")) {
      statement.setObject(1, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
      statement.setInt(2, maximumBatchSize);
      List<QueueEntry> entries = new ArrayList<>();
      try (ResultSet rows = statement.executeQuery()) {
        while (rows.next()) {
          entries.add(
              new QueueEntry(
                  rows.getObject(1, UUID.class),
                  rows.getObject(2, UUID.class),
                  rows.getObject(3, UUID.class)));
        }
      }
      return entries;
    } catch (SQLException exception) {
      throw persistenceUnavailable(exception);
    }
  }

  private static boolean expireOne(
      Connection connection, QueueEntry entry, ModelExecutionPolicy policy, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT requester_membership_id, reserved_cost_microunits FROM conversation_model_run "
                + "WHERE run_id = ? AND state = 'RUNNING' AND model_alias = ? "
                + "AND prompt_version = ? AND price_version = ? FOR UPDATE")) {
      statement.setObject(1, entry.runId());
      statement.setString(2, policy.modelAlias());
      statement.setString(3, policy.promptVersion());
      statement.setString(4, policy.priceVersion());
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) {
          deleteQueueEntry(connection, entry.runId());
          return false;
        }
        ClaimedModelRun claim =
            new ClaimedModelRun(
                entry.tenantId(),
                row.getObject(1, UUID.class),
                entry.conversationId(),
                entry.runId(),
                List.of(new ModelProviderMessage(MessageRole.USER, "expired")));
        long reservation = row.getLong(2);
        reconcileBudget(connection, claim, reservation, reservation, now);
        updateTerminalRun(
            connection,
            entry.runId(),
            ModelRunState.OUTCOME_UNKNOWN,
            ModelRunFailure.PROVIDER_UNAVAILABLE,
            reservation,
            null,
            now);
        deleteQueueEntry(connection, entry.runId());
        return true;
      }
    }
  }

  private static Completion completion(ModelExecutionPolicy policy, ModelProviderResult result) {
    return switch (result.outcome()) {
      case SUCCEEDED -> {
        try {
          long cost =
              policy.actualCostMicroUsd(
                  result.inputTokens(), result.cachedInputTokens(), result.outputTokens());
          yield new Completion(ModelRunState.SUCCEEDED, ModelRunFailure.NONE, cost, true);
        } catch (IllegalArgumentException | ArithmeticException exception) {
          yield new Completion(
              ModelRunState.OUTCOME_UNKNOWN,
              ModelRunFailure.PROVIDER_RESPONSE_INVALID,
              policy.maximumReservationMicroUsd(),
              false);
        }
      }
      case DEFINITIVE_REJECTION ->
          new Completion(ModelRunState.FAILED, ModelRunFailure.PROVIDER_REJECTED, 0, false);
      case DEFINITIVE_UNAVAILABLE ->
          new Completion(ModelRunState.FAILED, ModelRunFailure.PROVIDER_UNAVAILABLE, 0, false);
      case INVALID_RESPONSE ->
          new Completion(
              ModelRunState.OUTCOME_UNKNOWN,
              ModelRunFailure.PROVIDER_RESPONSE_INVALID,
              policy.maximumReservationMicroUsd(),
              false);
      case AMBIGUOUS ->
          new Completion(
              ModelRunState.OUTCOME_UNKNOWN,
              ModelRunFailure.PROVIDER_UNAVAILABLE,
              policy.maximumReservationMicroUsd(),
              false);
    };
  }

  private static void deleteQueueEntry(Connection connection, UUID runId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM conversation_model_run_queue WHERE run_id = ?")) {
      statement.setObject(1, runId);
      statement.executeUpdate();
    }
  }

  private <T> T tenantTransaction(UUID tenantId, SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        setTenant(connection, tenantId);
        setLimits(connection);
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException | RuntimeException exception) {
      throw persistenceUnavailable(exception);
    }
  }

  private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenantId.toString());
      statement.executeQuery().close();
    }
  }

  private static void setLimits(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("SET LOCAL lock_timeout = '100ms'");
      statement.execute("SET LOCAL statement_timeout = '750ms'");
    }
  }

  private static ConversationException persistenceUnavailable(Exception exception) {
    return new ConversationException(
        ConversationError.PERSISTENCE_UNAVAILABLE,
        "Conversation persistence is unavailable",
        exception);
  }

  private record QueueEntry(UUID runId, UUID tenantId, UUID conversationId) {}

  private record QueuedRun(
      UUID membershipId,
      String state,
      String modelAlias,
      String promptVersion,
      String priceVersion,
      long reservedCostMicroUsd,
      UUID userMessageId,
      String lifecycle) {
    private boolean matches(ModelExecutionPolicy policy) {
      return modelAlias.equals(policy.modelAlias())
          && promptVersion.equals(policy.promptVersion())
          && priceVersion.equals(policy.priceVersion())
          && reservedCostMicroUsd == policy.maximumReservationMicroUsd();
    }
  }

  private record LockedRun(long reservedCostMicroUsd, boolean cancellationRequested) {}

  private record Completion(
      ModelRunState state, ModelRunFailure failure, long actualCostMicroUsd, boolean recordUsage) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
