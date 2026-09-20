package com.sajtech.conversation.infrastructure.lifecycle;

import com.sajtech.identity.contract.v1.TenantLifecycleEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcTenantLifecycleRepository {
  private final DataSource dataSource;

  public JdbcTenantLifecycleRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void receive(TenantLifecycleEvent event, Instant receivedAt) {
    UUID eventId = UUID.fromString(event.getEventId());
    UUID tenantId = UUID.fromString(event.getTenantId());
    String state = event.getState().name().replace("TENANT_LIFECYCLE_EVENT_STATE_", "");
    Instant occurredAt =
        Instant.ofEpochSecond(event.getOccurredAt().getSeconds(), event.getOccurredAt().getNanos());
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        setLimits(connection);
        if (eventSeen(connection, eventId)) {
          connection.commit();
          return;
        }
        Projection current = lockProjection(connection, tenantId);
        String outcome = outcome(current, event.getLifecycleVersion(), state);
        recordInbox(
            connection, eventId, tenantId, event.getLifecycleVersion(), state, outcome, receivedAt);
        if ("APPLIED".equals(outcome)) {
          applyProjection(
              connection, tenantId, event.getLifecycleVersion(), state, occurredAt, receivedAt);
          if (!"ACTIVE".equals(state)) stopOwnedWork(connection, tenantId, receivedAt);
          if ("DELETED".equals(state)) purgeContent(connection, tenantId, receivedAt);
        } else if (!"DUPLICATE".equals(outcome) && current != null) {
          markUnordered(connection, tenantId, receivedAt);
        }
        connection.commit();
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Tenant lifecycle persistence is unavailable", exception);
    }
  }

  private static String outcome(Projection current, long version, String state) {
    if (current == null) return "APPLIED";
    if (version == current.version()) {
      return state.equals(current.state()) ? "DUPLICATE" : "CONFLICT";
    }
    if (version < current.version()) return "CONFLICT";
    if (version > current.version() + 1) return "GAP";
    if (current.purgeStarted() && ("ACTIVE".equals(state) || "PROVISIONING".equals(state))) {
      return "RESTORE_FORBIDDEN";
    }
    return "APPLIED";
  }

  private static boolean eventSeen(Connection connection, UUID eventId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT 1 FROM conversation_tenant_lifecycle_inbox WHERE event_id=?")) {
      statement.setObject(1, eventId);
      try (ResultSet row = statement.executeQuery()) {
        return row.next();
      }
    }
  }

  private static Projection lockProjection(Connection connection, UUID tenantId)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT lifecycle_version,lifecycle_state,purge_started_at FROM conversation_tenant_lifecycle WHERE tenant_id=? FOR UPDATE")) {
      statement.setObject(1, tenantId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()) return null;
        return new Projection(
            row.getLong("lifecycle_version"),
            row.getString("lifecycle_state"),
            row.getObject("purge_started_at", OffsetDateTime.class) != null);
      }
    }
  }

  private static void recordInbox(
      Connection connection,
      UUID eventId,
      UUID tenantId,
      long version,
      String state,
      String outcome,
      Instant receivedAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO conversation_tenant_lifecycle_inbox(event_id,tenant_id,lifecycle_version,lifecycle_state,outcome,received_at) VALUES (?,?,?,?,?,?)")) {
      statement.setObject(1, eventId);
      statement.setObject(2, tenantId);
      statement.setLong(3, version);
      statement.setString(4, state);
      statement.setString(5, outcome);
      statement.setObject(6, ts(receivedAt));
      statement.executeUpdate();
    }
  }

  private static void applyProjection(
      Connection connection,
      UUID tenantId,
      long version,
      String state,
      Instant occurredAt,
      Instant receivedAt)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            INSERT INTO conversation_tenant_lifecycle(
              tenant_id,lifecycle_version,lifecycle_state,ordered,purge_started_at,occurred_at,updated_at)
            VALUES (?,?,?,TRUE,CASE WHEN ?='DELETED' THEN ? ELSE NULL END,?,?)
            ON CONFLICT(tenant_id) DO UPDATE SET
              lifecycle_version=EXCLUDED.lifecycle_version,
              lifecycle_state=EXCLUDED.lifecycle_state,
              ordered=TRUE,
              purge_started_at=COALESCE(conversation_tenant_lifecycle.purge_started_at,
                                        EXCLUDED.purge_started_at),
              occurred_at=EXCLUDED.occurred_at,
              updated_at=EXCLUDED.updated_at
            """)) {
      statement.setObject(1, tenantId);
      statement.setLong(2, version);
      statement.setString(3, state);
      statement.setString(4, state);
      statement.setObject(5, ts(receivedAt));
      statement.setObject(6, ts(occurredAt));
      statement.setObject(7, ts(receivedAt));
      statement.executeUpdate();
    }
  }

  private static void markUnordered(Connection connection, UUID tenantId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_tenant_lifecycle SET ordered=FALSE,updated_at=? WHERE tenant_id=?")) {
      statement.setObject(1, ts(now));
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
  }

  private static void stopOwnedWork(Connection connection, UUID tenantId, Instant now)
      throws SQLException {
    setTenant(connection, tenantId);
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            WITH released AS (
              SELECT requester_membership_id, sum(reserved_cost_microunits)::bigint AS amount
              FROM conversation_model_run
              WHERE tenant_id=? AND state='QUEUED'
              GROUP BY requester_membership_id
            )
            UPDATE conversation_budget_account budget
            SET reserved_micro_usd=budget.reserved_micro_usd-released.amount,
                version=budget.version+1,
                updated_at=?
            FROM released
            WHERE budget.tenant_id=? AND budget.scope_type='MEMBERSHIP'
              AND budget.scope_id=released.requester_membership_id
            """)) {
      statement.setObject(1, tenantId);
      statement.setObject(2, ts(now));
      statement.setObject(3, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE conversation_budget_account
            SET reserved_micro_usd=reserved_micro_usd-COALESCE((
                  SELECT sum(reserved_cost_microunits)::bigint
                  FROM conversation_model_run
                  WHERE tenant_id=? AND state='QUEUED'),0),
                version=version+1,
                updated_at=?
            WHERE tenant_id=? AND scope_type='TENANT' AND scope_id=?
            """)) {
      statement.setObject(1, tenantId);
      statement.setObject(2, ts(now));
      statement.setObject(3, tenantId);
      statement.setObject(4, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET state='FAILED',actual_cost_microunits=0,failure_category='TENANT_INACTIVE',completed_at=? WHERE tenant_id=? AND state='QUEUED'")) {
      statement.setObject(1, ts(now));
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM conversation_model_run_queue WHERE tenant_id=? AND run_id IN (SELECT run_id FROM conversation_model_run WHERE tenant_id=? AND state='FAILED')")) {
      statement.setObject(1, tenantId);
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation_model_run SET cancellation_requested=TRUE WHERE tenant_id=? AND state='RUNNING'")) {
      statement.setObject(1, tenantId);
      statement.executeUpdate();
    }
  }

  private static void purgeContent(Connection connection, UUID tenantId, Instant now)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM conversation_message WHERE tenant_id=?")) {
      statement.setObject(1, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            "UPDATE conversation SET lifecycle='DELETED',owner_user_id=NULL,title_key_id='deleted',title_nonce=decode('000000000000000000000000','hex'),title_ciphertext=decode('00000000000000000000000000000000','hex'),aggregate_version=aggregate_version+1,last_activity_at=? WHERE tenant_id=? AND lifecycle<>'DELETED'")) {
      statement.setObject(1, ts(now));
      statement.setObject(2, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM conversation_subject_index WHERE tenant_id=?")) {
      statement.setObject(1, tenantId);
      statement.executeUpdate();
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
      statement.execute("SET LOCAL lock_timeout='250ms'");
      statement.execute("SET LOCAL statement_timeout='2s'");
    }
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  private record Projection(long version, String state, boolean purgeStarted) {}
}
