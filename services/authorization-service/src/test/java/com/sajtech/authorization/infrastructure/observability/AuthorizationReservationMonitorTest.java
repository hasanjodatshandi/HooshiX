package com.sajtech.authorization.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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
class AuthorizationReservationMonitorTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(
              DockerImageName.parse(
                      "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
                  .asCompatibleSubstituteFor("postgres"))
          .withDatabaseName("authorization_reservation_monitor")
          .withUsername("authorization_monitor_test")
          .withPassword("authorization_monitor_test_password");
  private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
  private DSLContext dsl;
  private SimpleMeterRegistry meters;
  private AuthorizationReservationMonitor monitor;

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
    var source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    var flyway =
        Flyway.configure()
            .dataSource(source)
            .cleanDisabled(false)
            .locations("classpath:db/migration")
            .load();
    flyway.clean();
    flyway.migrate();
    dsl = DSL.using(source, SQLDialect.POSTGRES);
    meters = new SimpleMeterRegistry();
    monitor =
        new AuthorizationReservationMonitor(
            dsl, new AuthorizationSecurityMetrics(meters), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void unresolvedPreparationPublishesIndexedAgeAndResolutionResetsGauge() {
    UUID request = UUID.randomUUID();
    UUID tenant = UUID.randomUUID();
    UUID membership = UUID.randomUUID();
    insert(request, tenant, "PREPARE_REMOVAL", membership, NOW.minusSeconds(901));

    monitor.sampleOnce();

    assertThat(meters.get("authorization.owner_reservation.oldest_unresolved_age").gauge().value())
        .isEqualTo(901);
    Object indexValue =
        dsl.fetchValue(
            "SELECT indexdef FROM pg_indexes WHERE indexname='authorization_idempotency_unresolved_prepare_idx'");
    String index = indexValue == null ? null : indexValue.toString();
    assertThat(index).contains("created_at", "request_id", "PREPARE_REMOVAL");

    insert(request, tenant, "FINALIZE_REMOVAL", membership, NOW);
    monitor.sampleOnce();

    assertThat(meters.get("authorization.owner_reservation.oldest_unresolved_age").gauge().value())
        .isZero();
  }

  private void insert(
      UUID request, UUID tenant, String operation, UUID reference, Instant createdAt) {
    dsl.execute(
        "INSERT INTO authorization_idempotency_record(request_id,tenant_id,operation,intent_fingerprint,fingerprint_version,fingerprint_key_id,outcome_code,outcome_reference,created_at) VALUES (?,?,?,?,?,?,'ACCEPTED',?,CAST(? AS TIMESTAMP WITH TIME ZONE))",
        request,
        tenant,
        operation,
        new byte[32],
        "v1",
        "k1",
        reference,
        OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
  }
}
