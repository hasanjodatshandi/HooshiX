package com.sajtech.identity.infrastructure.persistence;

import static com.sajtech.identity.application.transaction.model.TransactionProfile.MAINTENANCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.application.transaction.model.TransactionFailure;
import com.sajtech.identity.application.transaction.model.TransactionUnavailableException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class SpringTransactionRunnerIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("identity")
          .withUsername("identity_test")
          .withPassword("identity_test_password");

  private DriverManagerDataSource source;
  private DSLContext dsl;
  private SpringTransactionRunner transactions;

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
    source =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    transactions = new SpringTransactionRunner(new DataSourceTransactionManager(source), dsl);
    dsl.execute("DROP TABLE IF EXISTS identity_transaction_budget_probe");
    dsl.execute(
        "CREATE TABLE identity_transaction_budget_probe(id INTEGER PRIMARY KEY,value INTEGER NOT NULL)");
    dsl.execute("INSERT INTO identity_transaction_budget_probe(id,value) VALUES (1,0)");
  }

  @Test
  void appliesProfileSpecificTransactionLocalBudgets() {
    String interactiveStatement =
        transactions.required(() -> (String) dsl.fetchValue("SHOW statement_timeout"));
    String interactiveLock =
        transactions.required(() -> (String) dsl.fetchValue("SHOW lock_timeout"));
    String maintenanceStatement =
        transactions.required(MAINTENANCE, () -> (String) dsl.fetchValue("SHOW statement_timeout"));

    assertThat(interactiveStatement).isEqualTo("500ms");
    assertThat(interactiveLock).isEqualTo("100ms");
    assertThat(maintenanceStatement).isEqualTo("2s");
    assertThat((String) dsl.fetchValue("SHOW statement_timeout")).isEqualTo("0");
    assertThat((String) dsl.fetchValue("SHOW lock_timeout")).isEqualTo("0");
  }

  @Test
  void statementTimeoutCancelsLongQueryAndMapsSafeFailure() {
    assertThatThrownBy(
            () ->
                transactions.required(
                    () -> {
                      dsl.execute("SELECT pg_sleep(1)");
                      return null;
                    }))
        .isInstanceOf(TransactionUnavailableException.class)
        .satisfies(
            failure ->
                assertThat(((TransactionUnavailableException) failure).failure())
                    .isEqualTo(TransactionFailure.STATEMENT_TIMEOUT));
  }

  @Test
  void transactionDeadlineStopsWorkBeforeAnotherStatementUsesTheConnection() {
    assertThatThrownBy(
            () ->
                transactions.required(
                    () -> {
                      try {
                        Thread.sleep(1200);
                      } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Test interrupted", exception);
                      }
                      dsl.fetchValue("SELECT 1");
                      return null;
                    }))
        .isInstanceOf(TransactionUnavailableException.class)
        .satisfies(
            failure ->
                assertThat(((TransactionUnavailableException) failure).failure())
                    .isEqualTo(TransactionFailure.TRANSACTION_DEADLINE));
  }

  @Test
  void lockContentionFailsWithinLockBudget() throws Exception {
    try (Connection blocker = source.getConnection()) {
      blocker.setAutoCommit(false);
      try (PreparedStatement lock =
          blocker.prepareStatement(
              "UPDATE identity_transaction_budget_probe SET value=value+1 WHERE id=1")) {
        lock.executeUpdate();
      }

      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  transactions.required(
                      () -> {
                        dsl.execute(
                            "UPDATE identity_transaction_budget_probe SET value=value+1 WHERE id=1");
                        return null;
                      }))
          .isInstanceOf(TransactionUnavailableException.class)
          .satisfies(
              failure ->
                  assertThat(((TransactionUnavailableException) failure).failure())
                      .isEqualTo(TransactionFailure.LOCK_TIMEOUT));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
      blocker.rollback();
    }
  }

  @Test
  void poolExhaustionFailsWithinAcquisitionBudget() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(POSTGRES.getJdbcUrl());
    config.setUsername(POSTGRES.getUsername());
    config.setPassword(POSTGRES.getPassword());
    config.setMaximumPoolSize(1);
    config.setMinimumIdle(0);
    config.setConnectionTimeout(250);
    config.setPoolName("identity-transaction-budget-test");
    try (HikariDataSource pool = new HikariDataSource(config);
        Connection held = pool.getConnection()) {
      DSLContext pooledDsl =
          DSL.using(new TransactionAwareDataSourceProxy(pool), SQLDialect.POSTGRES);
      SpringTransactionRunner pooledTransactions =
          new SpringTransactionRunner(new DataSourceTransactionManager(pool), pooledDsl);
      long started = System.nanoTime();

      assertThatThrownBy(() -> pooledTransactions.required(() -> 1))
          .isInstanceOf(TransactionUnavailableException.class)
          .satisfies(
              failure ->
                  assertThat(((TransactionUnavailableException) failure).failure())
                      .isEqualTo(TransactionFailure.POOL_UNAVAILABLE));
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
      assertThat(held.isClosed()).isFalse();
    }
  }
}
