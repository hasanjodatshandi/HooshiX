package com.sajtech.conversation.infrastructure.persistence;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

final class TenantLifecycleProjection {
  private TenantLifecycleProjection() {}

  static void requireActive(Connection connection, UUID tenantId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT lifecycle_state,ordered FROM conversation_tenant_lifecycle WHERE tenant_id=?")) {
      statement.setObject(1, tenantId);
      try (ResultSet row = statement.executeQuery()) {
        if (!row.next()
            || !row.getBoolean("ordered")
            || !"ACTIVE".equals(row.getString("lifecycle_state"))) {
          throw new ConversationException(
              ConversationError.TENANT_INACTIVE, "Tenant lifecycle is not active");
        }
      }
    }
  }

  static boolean isActive(Connection connection, UUID tenantId) throws SQLException {
    try {
      requireActive(connection, tenantId);
      return true;
    } catch (ConversationException exception) {
      return false;
    }
  }
}
