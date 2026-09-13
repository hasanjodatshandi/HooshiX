package com.sajtech.conversation.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.conversation.application.ConversationError;
import com.sajtech.conversation.application.ConversationException;
import com.sajtech.conversation.application.model.ConversationActor;
import com.sajtech.conversation.domain.ConversationLifecycle;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
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
  @TempDir Path directory;
  private HikariDataSource runtime;
  private JdbcConversationRepository repository;

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
    config.setMaximumPoolSize(2);
    config.setMinimumIdle(1);
    config.setPoolName("conversation-rls-test");
    runtime = new HikariDataSource(config);
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 7);
    Path keyRing = directory.resolve("content.properties");
    Files.writeString(
        keyRing, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");
    repository =
        new JdbcConversationRepository(
            runtime,
            new AesGcmContentCrypto(
                new FileBackedContentKeyRing(keyRing, Clock.systemUTC(), Duration.ofMinutes(5)),
                new SecureRandom()));
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
          new String[] {
            "conversation",
            "conversation_message",
            "conversation_model_run",
            "conversation_mutation_request"
          }) {
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

  @Test
  void repositoryEnforcesPrivateOwnershipIdempotencyLifecycleAndRls() throws Exception {
    UUID tenant = UUID.randomUUID();
    UUID ownerMembership = UUID.randomUUID();
    ConversationActor owner = actor(tenant, ownerMembership);
    ConversationActor otherMember = actor(tenant, UUID.randomUUID());
    ConversationActor otherTenant = actor(UUID.randomUUID(), ownerMembership);
    UUID requestId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-09-13T08:00:00Z");

    var created = repository.create(owner, requestId, conversationId, "private title", createdAt);
    var replay =
        repository.create(
            owner, requestId, UUID.randomUUID(), "private title", createdAt.plusSeconds(1));

    assertThat(created.id()).isEqualTo(conversationId);
    assertThat(replay.id()).isEqualTo(conversationId);
    assertThatThrownBy(
            () ->
                repository.create(
                    owner,
                    requestId,
                    UUID.randomUUID(),
                    "different title",
                    createdAt.plusSeconds(1)))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.CONVERSATION_CONFLICT));
    assertThat(repository.getOwned(owner, conversationId).title()).isEqualTo("private title");
    assertThat(repository.listOwned(owner, 20, "").conversations()).containsExactly(created);
    assertNotFound(() -> repository.getOwned(otherMember, conversationId));
    assertNotFound(() -> repository.getOwned(otherTenant, conversationId));

    UUID archiveRequestId = UUID.randomUUID();
    var archived =
        repository.archiveOwned(
            owner, archiveRequestId, conversationId, 1, createdAt.plusSeconds(2));
    assertThat(archived.lifecycle()).isEqualTo(ConversationLifecycle.ARCHIVED);
    assertThat(archived.version()).isEqualTo(2);
    assertThat(
            repository.archiveOwned(
                owner, archiveRequestId, conversationId, 1, createdAt.plusSeconds(9)))
        .isEqualTo(archived);
    assertThatThrownBy(
            () ->
                repository.archiveOwned(
                    owner, archiveRequestId, UUID.randomUUID(), 1, createdAt.plusSeconds(9)))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.CONVERSATION_CONFLICT));
    assertThatThrownBy(
            () ->
                repository.archiveOwned(
                    owner, UUID.randomUUID(), conversationId, 2, createdAt.plusSeconds(9)))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.CONVERSATION_INVALID_STATE));

    UUID deleteRequestId = UUID.randomUUID();
    repository.deleteOwned(owner, deleteRequestId, conversationId, 2, createdAt.plusSeconds(3));
    repository.deleteOwned(owner, deleteRequestId, conversationId, 2, createdAt.plusSeconds(10));
    assertThatThrownBy(
            () ->
                repository.deleteOwned(
                    owner, UUID.randomUUID(), conversationId, 3, createdAt.plusSeconds(11)))
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error())
                    .isEqualTo(ConversationError.CONVERSATION_INVALID_STATE));
    assertNotFound(() -> repository.getOwned(owner, conversationId));
    assertThat(repository.listOwned(owner, 20, "").conversations()).isEmpty();
    assertThat(countVisibleWithoutContext()).isZero();
  }

  @Test
  void concurrentEqualCreateRequestReturnsOneConversation() throws Exception {
    ConversationActor owner = actor(UUID.randomUUID(), UUID.randomUUID());
    UUID requestId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-13T08:00:00Z");
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return repository.create(owner, requestId, UUID.randomUUID(), "same title", now);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return repository.create(owner, requestId, UUID.randomUUID(), "same title", now);
              });
      start.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS).id())
          .isEqualTo(second.get(5, TimeUnit.SECONDS).id());
    }
  }

  @Test
  void versionTwoBackfillsFoundationAggregateVersion() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .cleanDisabled(false)
        .load()
        .clean();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .target("1")
        .load()
        .migrate();
    UUID id = UUID.randomUUID();
    try (Connection connection = adminConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO conversation (conversation_id, tenant_id, owner_membership_id, "
                    + "title_key_id, title_nonce, title_ciphertext, lifecycle, aggregate_version, "
                    + "created_at, last_activity_at) VALUES (?, ?, ?, 'k1', ?, ?, 'ACTIVE', 0, ?, ?)")) {
      insert.setObject(1, id);
      insert.setObject(2, UUID.randomUUID());
      insert.setObject(3, UUID.randomUUID());
      insert.setBytes(4, new byte[12]);
      insert.setBytes(5, new byte[16]);
      OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
      insert.setObject(6, now);
      insert.setObject(7, now);
      assertThat(insert.executeUpdate()).isEqualTo(1);
    }

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();

    try (Connection connection = adminConnection();
        PreparedStatement query =
            connection.prepareStatement(
                "SELECT aggregate_version FROM conversation WHERE conversation_id = ?")) {
      query.setObject(1, id);
      try (ResultSet row = query.executeQuery()) {
        assertThat(row.next()).isTrue();
        assertThat(row.getLong(1)).isEqualTo(1);
      }
    }
  }

  private static ConversationActor actor(UUID tenant, UUID membership) {
    return new ConversationActor(UUID.randomUUID(), tenant, membership, "s".repeat(43));
  }

  private static void assertNotFound(Runnable query) {
    assertThatThrownBy(query::run)
        .isInstanceOfSatisfying(
            ConversationException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(ConversationError.CONVERSATION_NOT_FOUND));
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
                  + "VALUES (?, ?, ?, 'k1', ?, ?, 'ACTIVE', 1, ?, ?)")) {
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
