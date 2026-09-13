package com.sajtech.conversation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class ConversationRuntimeIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("conversation_runtime")
          .withUsername("conversation_runtime_test")
          .withPassword("runtime_test_password");

  @TempDir Path directory;

  @BeforeAll
  static void start() {
    POSTGRES.start();
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void productionProfileWiresPrivateReadinessWithProviderDisabled() throws Exception {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 4);
    Path keyFile = directory.resolve("content.properties");
    Files.writeString(
        keyFile, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");

    var context =
        new SpringApplicationBuilder(ConversationApplication.class)
            .registerShutdownHook(false)
            .run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--conversation.content-key-ring-path=" + keyFile,
                "--management.server.port=0",
                "--management.otlp.metrics.export.enabled=false");
    try {
      ConversationProperties properties = context.getBean(ConversationProperties.class);
      HealthIndicator readiness = context.getBean("conversationReadiness", HealthIndicator.class);
      assertThat(properties.providerRuntimeEnabled()).isFalse();
      assertThat(readiness.health().getStatus()).isEqualTo(Status.UP);
    } finally {
      context.close();
    }
  }
}
