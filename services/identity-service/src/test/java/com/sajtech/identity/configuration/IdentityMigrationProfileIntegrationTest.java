package com.sajtech.identity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class IdentityMigrationProfileIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("identity_migration_profile")
          .withUsername("identity_migration")
          .withPassword("migration_test_password");

  @BeforeAll
  static void start() {
    POSTGRES.start();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void migrationProfileAppliesServiceFlywayHistory() throws Exception {
    var context =
        new SpringApplicationBuilder(IdentityApplication.class)
            .registerShutdownHook(false)
            .run(
                "--spring.profiles.active=migration",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--management.otlp.metrics.export.enabled=false");
    try {
      try (var connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement();
          var result =
              statement.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getInt(1)).isEqualTo(11);
      }
      try (var connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  """
                  SELECT
                    to_regclass('identity_totp_enrollment') IS NOT NULL,
                    to_regclass('identity_totp_pending_enrollment') IS NOT NULL,
                    to_regclass('identity_mfa_recovery_code') IS NOT NULL,
                    to_regclass('identity_mfa_login_challenge') IS NOT NULL,
                    to_regclass('identity_external_identity') IS NOT NULL,
                    to_regclass('identity_oidc_evidence') IS NOT NULL,
                    EXISTS (
                      SELECT 1 FROM information_schema.columns
                      WHERE table_name = 'identity_refresh_family'
                        AND column_name = 'mfa_authenticated_at'
                    )
                  """)) {
        assertThat(result.next()).isTrue();
        for (int column = 1; column <= 7; column++) {
          assertThat(result.getBoolean(column)).isTrue();
        }
      }
      try (var connection =
              DriverManager.getConnection(
                  POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
          var statement = connection.createStatement()) {
        int inserted =
            statement.executeUpdate(
                """
                WITH observed AS (SELECT CURRENT_TIMESTAMP AS now)
                INSERT INTO identity_oidc_evidence(
                  evidence_id,request_id,operation,workload_identity,issuer,subject,
                  evidence_fingerprint,fingerprint_key_id,fingerprint_version,outcome,
                  evidence_issued_at,consumed_at,retain_until)
                SELECT decode(repeat('01',32),'hex'),
                  '123e4567-e89b-42d3-a456-426614174000'::uuid,
                  'ESTABLISH_SESSION','web-bff','https://accounts.google.com','google-subject',
                  decode(repeat('02',32),'hex'),'f1','oidc-evidence-hmac-v1',
                  'ACCOUNT_LINK_REQUIRED',now + INTERVAL '20 seconds',now,now + INTERVAL '10 minutes'
                FROM observed
                """);
        assertThat(inserted).isEqualTo(1);
      }
    } finally {
      if (context.isActive()) {
        context.close();
      }
    }
  }
}
