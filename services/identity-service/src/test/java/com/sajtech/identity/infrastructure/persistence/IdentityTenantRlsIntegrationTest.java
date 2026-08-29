package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class IdentityTenantRlsIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("identity")
          .withUsername("identity_migration_test")
          .withPassword("identity_migration_test_password");
  private static final String RUNTIME_ROLE = "identity_runtime_rls_test";
  private static final String RUNTIME_PASSWORD = "identity_runtime_rls_test_password";
  private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

  private DriverManagerDataSource adminSource;
  private DSLContext admin;
  private HikariDataSource runtimeSource;
  private DSLContext runtime;
  private SpringTransactionRunner transactions;
  private UUID tenantA;
  private UUID tenantB;
  private UUID userA;
  private UUID userB;

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
    adminSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway flyway =
        Flyway.configure()
            .dataSource(adminSource)
            .cleanDisabled(false)
            .locations("classpath:db/migration")
            .load();
    flyway.clean();
    flyway.migrate();
    admin = DSL.using(adminSource, SQLDialect.POSTGRES);
    createRuntimeRoleAndGrants();
    insertTenantFixtures();

    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(RUNTIME_ROLE);
    config.setPassword(RUNTIME_PASSWORD);
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(1);
    config.setPoolName("identity-rls-test");
    runtimeSource = new HikariDataSource(config);
    TransactionAwareDataSourceProxy proxy = new TransactionAwareDataSourceProxy(runtimeSource);
    runtime = DSL.using(proxy, SQLDialect.POSTGRES);
    transactions =
        new SpringTransactionRunner(new DataSourceTransactionManager(runtimeSource), runtime);
  }

  @AfterEach
  void closeRuntimePool() {
    if (runtimeSource != null) runtimeSource.close();
  }

  @Test
  void runtimeRoleIsNonOwnerNonBypassAndTenantTablesUseForcedRls() {
    var role =
        admin.fetchOne(
            "SELECT rolsuper,rolcreatedb,rolcreaterole,rolinherit,rolbypassrls FROM pg_roles WHERE rolname=?",
            RUNTIME_ROLE);

    assertThat(role).isNotNull();
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolcreatedb", Boolean.class)).isFalse();
    assertThat(role.get("rolcreaterole", Boolean.class)).isFalse();
    assertThat(role.get("rolinherit", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    assertThat(
            admin.fetchValue(
                "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname=current_database()"))
        .isNotEqualTo(RUNTIME_ROLE);

    assertForcedRls("identity_tenant_membership");
    assertForcedRls("identity_tenant_invitation");
    assertTenantPolicy("identity_tenant_membership", "identity_membership_tenant_policy");
    assertTenantPolicy("identity_tenant_invitation", "identity_invitation_tenant_policy");
  }

  @Test
  void documentedGlobalTenantCoordinationTablesRemainExplicitRlsExceptions() {
    assertNoRls("identity_tenant");
    assertNoRls("identity_user_membership_query");
    assertNoRls("identity_invitation_query");
    assertNoRls("identity_user_tenant_preference");
    assertNoRls("identity_authorization_outbox");
    assertNoRls("identity_membership_removal_intent");
    assertNoRls("identity_tenant_command_dedup");
  }

  @Test
  void missingAndMalformedTenantContextFailClosed() {
    assertThat(transactions.required(() -> membershipCountWithoutApplicationPredicate())).isZero();

    assertThatThrownBy(
            () ->
                transactions.required(
                    () -> {
                      setTenant("not-a-uuid");
                      return membershipCountWithoutApplicationPredicate();
                    }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void transactionLocalTenantContextRestrictsRowsAndDoesNotLeakAcrossPoolReuse() {
    assertThat(countForTenant(tenantA)).isEqualTo(1);
    assertThat(transactions.required(() -> membershipCountWithoutApplicationPredicate())).isZero();

    assertThatThrownBy(
            () ->
                transactions.required(
                    () -> {
                      setTenant(tenantB.toString());
                      assertThat(membershipCountWithoutApplicationPredicate()).isEqualTo(1);
                      throw new RollbackMarker();
                    }))
        .isInstanceOf(RollbackMarker.class);

    assertThat(transactions.required(() -> membershipCountWithoutApplicationPredicate())).isZero();
    assertThat(countForTenant(tenantB)).isEqualTo(1);
    assertThat(transactions.required(() -> membershipCountWithoutApplicationPredicate())).isZero();

    assertThatThrownBy(
            () ->
                transactions.required(
                    () -> {
                      setTenant(tenantA.toString());
                      runtime.execute(
                          "INSERT INTO identity_tenant_membership(tenant_id,membership_id,user_id,lifecycle,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
                          tenantB,
                          UUID.randomUUID(),
                          userB,
                          timestamp(),
                          timestamp());
                      return null;
                    }))
        .isInstanceOf(DataAccessException.class);
  }

  private int countForTenant(UUID tenant) {
    return transactions.required(
        () -> {
          setTenant(tenant.toString());
          return membershipCountWithoutApplicationPredicate();
        });
  }

  private int membershipCountWithoutApplicationPredicate() {
    Number count = (Number) runtime.fetchValue("SELECT count(*) FROM identity_tenant_membership");
    return count == null ? -1 : count.intValue();
  }

  private void setTenant(String tenant) {
    runtime.fetchValue("SELECT set_config('app.current_tenant_id', ?, true)", tenant);
  }

  private void assertForcedRls(String table) {
    var row =
        admin.fetchOne(
            "SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)",
            table);
    assertThat(row).isNotNull();
    assertThat(row.get("relrowsecurity", Boolean.class)).isTrue();
    assertThat(row.get("relforcerowsecurity", Boolean.class)).isTrue();
  }

  private void assertTenantPolicy(String table, String policy) {
    var row =
        admin.fetchOne(
            "SELECT qual,with_check FROM pg_policies WHERE schemaname='public' AND tablename=? AND policyname=?",
            table,
            policy);
    assertThat(row).isNotNull();
    assertThat(row.get("qual", String.class))
        .contains("current_setting")
        .contains("app.current_tenant_id");
    assertThat(row.get("with_check", String.class))
        .contains("current_setting")
        .contains("app.current_tenant_id");
  }

  private void assertNoRls(String table) {
    var row =
        admin.fetchOne(
            "SELECT relrowsecurity,relforcerowsecurity FROM pg_class WHERE oid=CAST(? AS regclass)",
            table);
    assertThat(row).isNotNull();
    assertThat(row.get("relrowsecurity", Boolean.class)).isFalse();
    assertThat(row.get("relforcerowsecurity", Boolean.class)).isFalse();
  }

  private void createRuntimeRoleAndGrants() {
    admin.execute(
        "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='"
            + RUNTIME_ROLE
            + "') THEN CREATE ROLE "
            + RUNTIME_ROLE
            + " LOGIN PASSWORD '"
            + RUNTIME_PASSWORD
            + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS; END IF; END $$");
    admin.execute("REVOKE CONNECT ON DATABASE identity FROM PUBLIC");
    admin.execute("GRANT CONNECT ON DATABASE identity TO " + RUNTIME_ROLE);
    admin.execute("GRANT USAGE ON SCHEMA public TO " + RUNTIME_ROLE);
    admin.execute(
        "GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA public TO " + RUNTIME_ROLE);
    admin.execute("GRANT USAGE,SELECT,UPDATE ON ALL SEQUENCES IN SCHEMA public TO " + RUNTIME_ROLE);
  }

  private void insertTenantFixtures() {
    userA = UUID.randomUUID();
    userB = UUID.randomUUID();
    tenantA = UUID.randomUUID();
    tenantB = UUID.randomUUID();
    insertUser(userA);
    insertUser(userB);
    insertTenant(tenantA, userA, "Tenant A", "tenant-a");
    insertTenant(tenantB, userB, "Tenant B", "tenant-b");
    insertMembership(tenantA, UUID.randomUUID(), userA);
    insertMembership(tenantB, UUID.randomUUID(), userB);
  }

  private void insertUser(UUID user) {
    admin.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        user,
        timestamp(),
        timestamp());
  }

  private void insertTenant(UUID tenant, UUID creator, String name, String slug) {
    admin.execute(
        "INSERT INTO identity_tenant(tenant_id,name,slug,lifecycle,creator_user_id,version,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,1,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        tenant,
        name,
        slug,
        creator,
        timestamp(),
        timestamp());
  }

  private void insertMembership(UUID tenant, UUID membership, UUID user) {
    admin.execute(
        "INSERT INTO identity_tenant_membership(tenant_id,membership_id,user_id,lifecycle,created_at,updated_at) VALUES (?,?,?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        tenant,
        membership,
        user,
        timestamp(),
        timestamp());
  }

  private static OffsetDateTime timestamp() {
    return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
  }

  private static final class RollbackMarker extends RuntimeException {}
}
