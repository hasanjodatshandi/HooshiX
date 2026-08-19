package com.sajtech.authorization.configuration;

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
class AuthorizationMigrationProfileIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("authorization_migration_profile")
          .withUsername("authorization_migration")
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
        new SpringApplicationBuilder(AuthorizationApplication.class)
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
        assertThat(result.getInt(1)).isEqualTo(1);
      }
    } finally {
      if (context.isActive()) {
        context.close();
      }
    }
  }
}
