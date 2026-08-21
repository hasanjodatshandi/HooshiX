package com.sajtech.authorization.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.application.model.FingerprintDigest;
import com.sajtech.authorization.application.model.PermissionModel;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class AuthorizationRlsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("authorization_rls")
          .withUsername("authorization_migration_test")
          .withPassword("authorization_migration_test_password");
  private static final String ROLE = "authorization_runtime_rls_test";
  private static final String PASSWORD = "authorization_runtime_rls_test_password";
  private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
  private DSLContext admin;
  private HikariDataSource pool;
  private DSLContext runtime;
  private UUID tenantA;
  private UUID tenantB;

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @BeforeEach
  void reset() {
    var adminSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(adminSource)
        .cleanDisabled(false)
        .locations("classpath:db/migration")
        .load()
        .clean();
    Flyway.configure().dataSource(adminSource).locations("classpath:db/migration").load().migrate();
    admin = DSL.using(adminSource, SQLDialect.POSTGRES);
    admin.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='"
            + ROLE
            + "') THEN CREATE ROLE "
            + ROLE
            + " LOGIN PASSWORD '"
            + PASSWORD
            + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS; END IF; END $$");
    admin.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
    admin.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + ROLE);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(ROLE);
    config.setPassword(PASSWORD);
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setPoolName("authorization-rls-test");
    pool = new HikariDataSource(config);
    runtime = DSL.using(pool, SQLDialect.POSTGRES);
    var store = new JooqAuthorizationStore(runtime, () -> {});
    store.projectPermissionCatalog(
        List.of(
                "tenant.read",
                "tenant.delete",
                "role.read",
                "role.create",
                "role.update",
                "role.archive",
                "role.permission.manage",
                "membership.read",
                "membership.role.assign",
                "membership.permission.manage",
                "membership.owner.assign")
            .stream()
            .map(key -> new PermissionModel(key, "TENANT", "ACTIVE"))
            .toList(),
        1,
        NOW);
    tenantA = UUID.randomUUID();
    tenantB = UUID.randomUUID();
    store.provisionOwner(
        UUID.randomUUID(), digest(1), tenantA, UUID.randomUUID(), UUID.randomUUID(), NOW);
    store.provisionOwner(
        UUID.randomUUID(), digest(2), tenantB, UUID.randomUUID(), UUID.randomUUID(), NOW);
  }

  @AfterEach
  void closePool() {
    if (pool != null) pool.close();
  }

  @Test
  void runtimeRoleIsNonOwnerNonBypassAndEveryTenantTableUsesForcedRls() {
    var role =
        admin.fetchOne(
            "SELECT rolsuper,rolcreatedb,rolcreaterole,rolinherit,rolbypassrls FROM pg_roles WHERE rolname=?",
            ROLE);
    assertThat(role).isNotNull();
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolcreatedb", Boolean.class)).isFalse();
    assertThat(role.get("rolcreaterole", Boolean.class)).isFalse();
    assertThat(role.get("rolinherit", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            admin.fetchValue(
                "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()"))
        .isNotEqualTo(ROLE);
    for (String table :
        List.of(
            "authorization_membership_projection",
            "authorization_role",
            "authorization_role_permission",
            "authorization_membership_role",
            "authorization_membership_permission_override",
            "authorization_owner_safety_guard",
            "authorization_membership_removal_reservation")) {
      var row =
          admin.fetchOne(
              "SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)",
              table);
      assertThat(row).isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class)).isTrue();
    }
  }

  @Test
  void missingAndMalformedTenantContextFailClosed() {
    assertThat(rawRoleCount()).isZero();
    assertThatThrownBy(
            () ->
                runtime.transaction(
                    c -> {
                      DSLContext tx = DSL.using(c);
                      tx.fetchValue(
                          "SELECT set_config('app.current_tenant_id', 'not-a-uuid', true)");
                      tx.fetchValue("SELECT count(*) FROM authorization_role");
                    }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void transactionLocalContextAndStatementBudgetDoNotLeakAcrossCommitOrRollback() {
    Integer pid =
        runtime.transactionResult(
            c -> {
              DSLContext tx = DSL.using(c);
              JooqAuthorizationStore.configureCheckPermissionTransaction(tx, tenantA);
              assertThat(tx.fetchValue("SHOW statement_timeout")).isEqualTo("100ms");
              assertThat(
                      ((Number) tx.fetchValue("SELECT count(*) FROM authorization_role"))
                          .intValue())
                  .isEqualTo(3);
              return ((Number) tx.fetchValue("SELECT pg_backend_pid()")).intValue();
            });
    assertThat(rawRoleCount()).isZero();
    assertThat(((Number) runtime.fetchValue("SELECT pg_backend_pid()")).intValue()).isEqualTo(pid);
    assertThatThrownBy(
            () ->
                runtime.transaction(
                    c -> {
                      DSLContext tx = DSL.using(c);
                      tx.fetchValue(
                          "SELECT set_config('app.current_tenant_id', ?, true)",
                          tenantB.toString());
                      assertThat(
                              ((Number) tx.fetchValue("SELECT count(*) FROM authorization_role"))
                                  .intValue())
                          .isEqualTo(3);
                      throw new RollbackMarker();
                    }))
        .isInstanceOf(RollbackMarker.class);
    assertThat(rawRoleCount()).isZero();
    assertThat(((Number) runtime.fetchValue("SELECT pg_backend_pid()")).intValue()).isEqualTo(pid);
  }

  private int rawRoleCount() {
    Number n = (Number) runtime.fetchValue("SELECT count(*) FROM authorization_role");
    return n.intValue();
  }

  private static FingerprintDigest digest(int seed) {
    byte[] value = new byte[32];
    Arrays.fill(value, (byte) seed);
    return new FingerprintDigest("v1", "k1", Map.of("k1", value));
  }

  private static final class RollbackMarker extends RuntimeException {}
}
