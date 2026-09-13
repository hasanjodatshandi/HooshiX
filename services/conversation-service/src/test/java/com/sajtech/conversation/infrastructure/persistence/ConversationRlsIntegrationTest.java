package com.sajtech.conversation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class ConversationRlsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("conversation_rls")
          .withUsername("conversation_migration")
          .withPassword("migration_test_password");
  private static final String RUNTIME_ROLE = "conversation_runtime_test";
  private static final String RUNTIME_PASSWORD = "runtime_test_password";
  private HikariDataSource runtime;

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void migrateAndCreateRuntimeRole() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    try (Connection connection = adminConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + RUNTIME_ROLE
              + "') THEN CREATE ROLE "
              + RUNTIME_ROLE
              + " LOGIN PASSWORD '"
              + RUNTIME_PASSWORD
              + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS; END IF; END $$");
      statement.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
      statement.execute(
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO " + RUNTIME_ROLE);
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(RUNTIME_ROLE);
    config.setPassword(RUNTIME_PASSWORD);
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setPoolName("conversation-rls-test");
    runtime = new HikariDataSource(config);
  }

  @AfterEach
  void closePool() {
    runtime.close();
  }

  @Test
  void schemaUsesForcedRlsAndRuntimeRoleCannotBypassIt() throws Exception {
    try (Connection connection = adminConnection();
        PreparedStatement role =
            connection.prepareStatement(
                "SELECT rolsuper, rolcreatedb, rolcreaterole, rolinherit, rolbypassrls "
                    + "FROM pg_roles WHERE rolname = ?")) {
      role.setString(1, RUNTIME_ROLE);
      try (ResultSet result = role.executeQuery()) {
        assertThat(result.next()).isTrue();
        for (int column = 1; column <= 5; column++) assertThat(result.getBoolean(column)).isFalse();
      }
      for (String table :
          new String[] {"conversation", "conversation_message", "conversation_model_run"}) {
        try (PreparedStatement security =
            connection.prepareStatement(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = CAST(? AS regclass)")) {
          security.setString(1, table);
          try (ResultSet result = security.executeQuery()) {
            assertThat(result.next()).isTrue();
            assertThat(result.getBoolean(1)).isTrue();
            assertThat(result.getBoolean(2)).isTrue();
          }
        }
      }
    }
  }

  @Test
  void tenantContextIsTransactionLocalAndFailsClosedAcrossPoolReuse() throws Exception {
    UUID tenantA = UUID.randomUUID();
    UUID tenantB = UUID.randomUUID();
    insertConversation(tenantA);
    insertConversation(tenantB);

    assertThat(countVisibleWithoutContext()).isZero();
    assertThat(countVisible(tenantA)).isEqualTo(1);
    assertThat(countVisibleWithoutContext()).isZero();
    assertThat(countVisible(tenantB)).isEqualTo(1);
    assertThat(countVisibleWithoutContext()).isZero();

    assertThatThrownBy(
            () -> {
              try (Connection connection = runtime.getConnection()) {
                connection.setAutoCommit(false);
                setTenant(connection, "not-a-uuid");
                count(connection);
              }
            })
        .isInstanceOf(SQLException.class);
  }

  private void insertConversation(UUID tenant) throws Exception {
    try (Connection connection = runtime.getConnection()) {
      connection.setAutoCommit(false);
      setTenant(connection, tenant.toString());
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO conversation "
                  + "(conversation_id, tenant_id, owner_membership_id, title_key_id, title_nonce, "
                  + "title_ciphertext, lifecycle, aggregate_version, created_at, last_activity_at) "
                  + "VALUES (?, ?, ?, 'k1', ?, ?, 'ACTIVE', 0, ?, ?)")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, tenant);
        insert.setObject(3, UUID.randomUUID());
        insert.setBytes(4, new byte[12]);
        insert.setBytes(5, new byte[16]);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        insert.setObject(6, now);
        insert.setObject(7, now);
        assertThat(insert.executeUpdate()).isEqualTo(1);
      }
      connection.commit();
    }
  }

  private int countVisible(UUID tenant) throws Exception {
    try (Connection connection = runtime.getConnection()) {
      connection.setAutoCommit(false);
      setTenant(connection, tenant.toString());
      int count = count(connection);
      connection.commit();
      return count;
    }
  }

  private int countVisibleWithoutContext() throws Exception {
    try (Connection connection = runtime.getConnection()) {
      return count(connection);
    }
  }

  private static int count(Connection connection) throws Exception {
    try (PreparedStatement query =
            connection.prepareStatement("SELECT count(*) FROM conversation");
        ResultSet result = query.executeQuery()) {
      assertThat(result.next()).isTrue();
      return result.getInt(1);
    }
  }

  private static void setTenant(Connection connection, String tenant) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
      statement.setString(1, tenant);
      statement.executeQuery().close();
    }
  }

  private static Connection adminConnection() throws Exception {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
