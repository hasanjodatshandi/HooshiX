package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class IdentityTenantContextIntegrityIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("identity_tenant_integrity")
          .withUsername("identity_migration")
          .withPassword("migration_test_password");
  private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");

  private DSLContext dsl;
  private UUID userA;
  private UUID userB;
  private UUID tenantA;
  private UUID tenantB;
  private UUID membershipA;
  private UUID membershipB;
  private UUID membershipAForUserB;

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
    DriverManagerDataSource source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway flyway =
        Flyway.configure()
            .dataSource(source)
            .cleanDisabled(false)
            .locations("classpath:db/migration")
            .load();
    flyway.clean();
    flyway.migrate();
    dsl = DSL.using(source, SQLDialect.POSTGRES);
    userA = UUID.randomUUID();
    userB = UUID.randomUUID();
    tenantA = UUID.randomUUID();
    tenantB = UUID.randomUUID();
    membershipA = UUID.randomUUID();
    membershipB = UUID.randomUUID();
    membershipAForUserB = UUID.randomUUID();
    insertUser(userA);
    insertUser(userB);
    insertTenant(tenantA, userA, "Tenant A", "tenant-a");
    insertTenant(tenantB, userB, "Tenant B", "tenant-b");
    insertMembership(tenantA, membershipA, userA);
    insertMembership(tenantB, membershipB, userB);
    insertMembership(tenantA, membershipAForUserB, userB);
  }

  @Test
  void refreshFamilyRejectsMembershipOwnedByAnotherUserInSelectedTenant() {
    OffsetDateTime now = timestamp();
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO identity_refresh_family(refresh_family_id,session_id,user_id,state,session_mode,authentication_method,authenticated_at,created_at,last_activity_at,idle_expires_at,absolute_expires_at,updated_at,selected_tenant_id,selected_membership_id) VALUES (?,?,?,'ACTIVE','TENANT_AUTHENTICATED','LOCAL_PASSWORD',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE),?,?)",
                    UUID.randomUUID(),
                    "s".repeat(43),
                    userA,
                    now,
                    now,
                    now,
                    now.plusDays(7),
                    now.plusDays(30),
                    now,
                    tenantA,
                    membershipAForUserB))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void acceptedInvitationRejectsMembershipOwnedByAnotherUserInSameTenant() {
    UUID contact = UUID.randomUUID();
    OffsetDateTime now = timestamp();
    dsl.execute(
        "INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,verified_at,primary_active,created_at,updated_at) VALUES (?,?, 'EMAIL',?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),TRUE,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        contact,
        userA,
        "user-a@example.com",
        "user-a@example.com",
        now,
        now,
        now);

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "INSERT INTO identity_tenant_invitation(tenant_id,invitation_id,target_user_id,target_contact_id,invited_by_user_id,state,expires_at,accepted_membership_id,created_at,updated_at) VALUES (?,?,?,?,?,'ACCEPTED',CAST(? AS TIMESTAMP WITH TIME ZONE),?,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
                    tenantA,
                    UUID.randomUUID(),
                    userA,
                    contact,
                    userA,
                    now.plusDays(7),
                    membershipAForUserB,
                    now,
                    now))
        .isInstanceOf(DataAccessException.class);
  }

  private void insertUser(UUID user) {
    dsl.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        user,
        timestamp(),
        timestamp());
  }

  private void insertTenant(UUID tenant, UUID creator, String name, String slug) {
    dsl.execute(
        "INSERT INTO identity_tenant(tenant_id,name,slug,lifecycle,creator_user_id,version,created_at,updated_at) VALUES (?,?,?,'ACTIVE',?,1,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        tenant,
        name,
        slug,
        creator,
        timestamp(),
        timestamp());
  }

  private void insertMembership(UUID tenant, UUID membership, UUID user) {
    dsl.execute(
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
}
