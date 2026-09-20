package com.sajtech.conversation.infrastructure.erasure;

import com.sajtech.identity.contract.v1.ErasureCommandEvent;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcConversationErasureRepository {
  private static final String ACTIONS = "conversation_content,feedback,subject_index";
  private final DataSource dataSource;

  public JdbcConversationErasureRepository(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public void receive(ErasureCommandEvent event, Instant now) {
    transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  """
                  INSERT INTO conversation_erasure_inbox(
                    event_id,erasure_request_id,participant_policy_version,state,attempt_count,
                    next_attempt_at,received_at,retain_until)
                  VALUES (?,?,?,'PENDING',0,?,?,?)
                  ON CONFLICT(erasure_request_id) DO UPDATE SET
                    state=CASE WHEN conversation_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                               THEN 'PENDING' ELSE conversation_erasure_inbox.state END,
                    attempt_count=CASE WHEN conversation_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                       THEN 0 ELSE conversation_erasure_inbox.attempt_count END,
                    next_attempt_at=CASE WHEN conversation_erasure_inbox.state IN ('PENDING','PROCESSING','EXHAUSTED')
                                         THEN EXCLUDED.next_attempt_at ELSE conversation_erasure_inbox.next_attempt_at END,
                    lease_until=CASE WHEN conversation_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                     THEN NULL ELSE conversation_erasure_inbox.lease_until END,
                    last_error_class=CASE WHEN conversation_erasure_inbox.state IN ('PENDING','EXHAUSTED')
                                          THEN NULL ELSE conversation_erasure_inbox.last_error_class END,
                    retain_until=GREATEST(conversation_erasure_inbox.retain_until,EXCLUDED.retain_until),
                    redrive_requested=(conversation_erasure_inbox.state='PROCESSING')
                  WHERE conversation_erasure_inbox.event_id<>EXCLUDED.event_id
                    AND conversation_erasure_inbox.state<>'COMPLETED'
                  """)) {
            statement.setObject(1, UUID.fromString(event.getEventId()));
            statement.setObject(2, UUID.fromString(event.getErasureRequestId()));
            statement.setString(3, event.getParticipantPolicyVersion());
            statement.setObject(4, ts(now));
            statement.setObject(5, ts(now));
            statement.setObject(6, ts(now.plus(Duration.ofDays(35))));
            statement.executeUpdate();
          }
          return null;
        });
  }

  public Optional<InboxItem> claim(Instant now, Duration lease) {
    return transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT event_id,erasure_request_id,participant_policy_version,attempt_count FROM conversation_erasure_inbox WHERE state IN ('PENDING','PROCESSING') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
            statement.setObject(1, ts(now));
            statement.setObject(2, ts(now));
            try (ResultSet row = statement.executeQuery()) {
              if (!row.next()) return Optional.empty();
              UUID eventId = row.getObject("event_id", UUID.class);
              try (PreparedStatement update =
                  connection.prepareStatement(
                      "UPDATE conversation_erasure_inbox SET state='PROCESSING',lease_until=? WHERE event_id=?")) {
                update.setObject(1, ts(now.plus(lease)));
                update.setObject(2, eventId);
                update.executeUpdate();
              }
              return Optional.of(
                  new InboxItem(
                      eventId,
                      row.getObject("erasure_request_id", UUID.class),
                      row.getString("participant_policy_version"),
                      row.getInt("attempt_count")));
            }
          }
        });
  }

  public void eraseSubject(UUID userId, Instant now) {
    List<UUID> tenants = subjectTenants(userId);
    for (UUID tenantId : tenants) eraseTenantSubject(userId, tenantId, now);
  }

  private List<UUID> subjectTenants(UUID userId) {
    return transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "SELECT DISTINCT tenant_id FROM conversation_subject_index WHERE user_id=? ORDER BY tenant_id")) {
            statement.setObject(1, userId);
            try (ResultSet rows = statement.executeQuery()) {
              List<UUID> tenants = new ArrayList<>();
              while (rows.next()) tenants.add(rows.getObject(1, UUID.class));
              return List.copyOf(tenants);
            }
          }
        });
  }

  private void eraseTenantSubject(UUID userId, UUID tenantId, Instant now) {
    transaction(
        connection -> {
          setTenant(connection, tenantId);
          releaseQueuedBudgets(connection, userId, tenantId, now);
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation_model_run SET state='FAILED',actual_cost_microunits=0,failure_category='TENANT_INACTIVE',completed_at=? WHERE tenant_id=? AND state='QUEUED' AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)")) {
            statement.setObject(1, ts(now));
            statement.setObject(2, tenantId);
            statement.setObject(3, userId);
            statement.setObject(4, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation_model_run SET cancellation_requested=TRUE WHERE tenant_id=? AND state='RUNNING' AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation_model_run SET user_message_id=NULL WHERE tenant_id=? AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM conversation_model_run_queue WHERE tenant_id=? AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?) AND run_id IN (SELECT run_id FROM conversation_model_run WHERE tenant_id=? AND state='FAILED')")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, tenantId);
            statement.setObject(4, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM conversation_message WHERE tenant_id=? AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)")) {
            statement.setObject(1, tenantId);
            statement.setObject(2, userId);
            statement.setObject(3, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation SET lifecycle='DELETED',owner_user_id=NULL,title_key_id='deleted',title_nonce=decode('000000000000000000000000','hex'),title_ciphertext=decode('00000000000000000000000000000000','hex'),aggregate_version=aggregate_version+1,last_activity_at=? WHERE tenant_id=? AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)")) {
            statement.setObject(1, ts(now));
            statement.setObject(2, tenantId);
            statement.setObject(3, userId);
            statement.setObject(4, tenantId);
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "DELETE FROM conversation_subject_index WHERE user_id=? AND tenant_id=?")) {
            statement.setObject(1, userId);
            statement.setObject(2, tenantId);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private static void releaseQueuedBudgets(
      Connection connection, UUID userId, UUID tenantId, Instant now) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            WITH released AS (
              SELECT requester_membership_id, sum(reserved_cost_microunits)::bigint amount
              FROM conversation_model_run
              WHERE tenant_id=? AND state='QUEUED'
                AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)
              GROUP BY requester_membership_id
            )
            UPDATE conversation_budget_account budget
            SET reserved_micro_usd=budget.reserved_micro_usd-released.amount,
                version=budget.version+1,updated_at=?
            FROM released
            WHERE budget.tenant_id=? AND budget.scope_type='MEMBERSHIP'
              AND budget.scope_id=released.requester_membership_id
            """)) {
      statement.setObject(1, tenantId);
      statement.setObject(2, userId);
      statement.setObject(3, tenantId);
      statement.setObject(4, ts(now));
      statement.setObject(5, tenantId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE conversation_budget_account SET
              reserved_micro_usd=reserved_micro_usd-COALESCE((
                SELECT sum(reserved_cost_microunits)::bigint FROM conversation_model_run
                WHERE tenant_id=? AND state='QUEUED'
                  AND conversation_id IN (SELECT conversation_id FROM conversation_subject_index WHERE user_id=? AND tenant_id=?)),0),
              version=version+1,updated_at=?
            WHERE tenant_id=? AND scope_type='TENANT' AND scope_id=?
            """)) {
      statement.setObject(1, tenantId);
      statement.setObject(2, userId);
      statement.setObject(3, tenantId);
      statement.setObject(4, ts(now));
      statement.setObject(5, tenantId);
      statement.setObject(6, tenantId);
      statement.executeUpdate();
    }
  }

  public void complete(InboxItem item, Instant now) {
    transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation_erasure_inbox SET state='COMPLETED',lease_until=NULL,redrive_requested=FALSE,last_error_class=NULL,completed_at=? WHERE event_id=? AND state='PROCESSING'")) {
            statement.setObject(1, ts(now));
            statement.setObject(2, item.eventId());
            if (statement.executeUpdate() != 1) {
              throw new IllegalStateException("Conversation erasure completion failed");
            }
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO conversation_erasure_evidence(evidence_id,erasure_request_id,policy_version,event_code,action_categories,occurred_at,integrity_version) VALUES (?,?,?,'ERASURE_COMPLETED',?,?,'v1')")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, item.erasureRequestId());
            statement.setString(3, item.participantPolicyVersion());
            statement.setString(4, ACTIONS);
            statement.setObject(5, ts(now));
            statement.executeUpdate();
          }
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO conversation_erasure_receipt_outbox(event_id,erasure_request_id,participant_policy_version,outcome,action_categories,state,attempt_count,next_attempt_at,occurred_at,retain_until,updated_at) VALUES (?,?,?,'COMPLETED',?,'PENDING',0,?,?,?,?)")) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, item.erasureRequestId());
            statement.setString(3, item.participantPolicyVersion());
            statement.setString(4, ACTIONS);
            statement.setObject(5, ts(now));
            statement.setObject(6, ts(now));
            statement.setObject(7, ts(now.plus(Duration.ofDays(35))));
            statement.setObject(8, ts(now));
            statement.executeUpdate();
          }
          return null;
        });
  }

  public void reschedule(UUID eventId, int attempt, Instant next, String error, boolean exhausted) {
    transaction(
        connection -> {
          try (PreparedStatement statement =
              connection.prepareStatement(
                  "UPDATE conversation_erasure_inbox SET state=CASE WHEN redrive_requested THEN 'PENDING' ELSE ? END,attempt_count=CASE WHEN redrive_requested THEN 0 ELSE ? END,next_attempt_at=CASE WHEN redrive_requested THEN next_attempt_at ELSE ? END,lease_until=NULL,last_error_class=CASE WHEN redrive_requested THEN NULL ELSE ? END,redrive_requested=FALSE WHERE event_id=? AND state='PROCESSING'")) {
            statement.setString(1, exhausted ? "EXHAUSTED" : "PENDING");
            statement.setInt(2, attempt);
            statement.setObject(3, ts(next));
            statement.setString(4, error);
            statement.setObject(5, eventId);
            statement.executeUpdate();
          }
          return null;
        });
  }

  private <T> T transaction(SqlWork<T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (Statement limits = connection.createStatement()) {
          limits.execute("SET LOCAL lock_timeout='250ms'");
          limits.execute("SET LOCAL statement_timeout='2s'");
        }
        T result = work.execute(connection);
        connection.commit();
        return result;
      } catch (RuntimeException | SQLException exception) {
        connection.rollback();
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Conversation erasure persistence is unavailable", exception);
    }
  }

  private static void setTenant(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenantId.toString());
      statement.executeQuery().close();
    }
  }

  private static OffsetDateTime ts(Instant value) {
    return value.atOffset(ZoneOffset.UTC);
  }

  public record InboxItem(
      UUID eventId, UUID erasureRequestId, String participantPolicyVersion, int attemptCount) {}

  @FunctionalInterface
  private interface SqlWork<T> {
    T execute(Connection connection) throws SQLException;
  }
}
