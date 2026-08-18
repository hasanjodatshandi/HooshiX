package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.registration.model.*;
import com.sajtech.identity.domain.registration.valueobject.*;
import java.time.*;
import java.util.Objects;
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
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class IdentityRegistrationPersistenceIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("identity")
          .withUsername("identity_test")
          .withPassword("identity_test_password");
  private DSLContext dsl;
  private JooqRegistrationStore store;
  private JooqNotificationOutboxStore outboxStore;
  private SpringTransactionRunner tx;

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
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
    store = new JooqRegistrationStore(dsl);
    outboxStore = new JooqNotificationOutboxStore(dsl);
    tx = new SpringTransactionRunner(new DataSourceTransactionManager(source));
  }

  @Test
  void registrationAndConfirmationPreserveReservationCredentialPrimaryAndActivationInvariants() {
    Instant now = Instant.parse("2026-08-18T00:00:00Z");
    CanonicalContact contact =
        new CanonicalContact(RegistrationChannel.EMAIL, "person@example.com", "Person@Example.com");
    UUID user = UUID.randomUUID(),
        contactId = UUID.randomUUID(),
        challenge = UUID.randomUUID(),
        outbox = UUID.randomUUID();
    PreparedRegistration p =
        new PreparedRegistration(
            user,
            contactId,
            challenge,
            outbox,
            UUID.randomUUID(),
            contact,
            new RegistrationProfile("First", "Last", null),
            "$argon2id$test",
            RegistrationLocale.EN,
            new byte[32],
            "k1",
            new EncryptedHandoff("k2", new byte[12], new byte[32]),
            now,
            now.plusSeconds(600));
    UUID dedupRequest = UUID.randomUUID();
    tx.required(
        () -> {
          store.lockContactKey(contact);
          store.insertRegistration(p);
          assertThat(
                  store.tryInsertDedup(
                      dedupRequest, "REGISTER", new byte[32], "v1", "k1", "ACCEPTED", now))
              .isTrue();
          return null;
        });
    assertThat(dsl.fetchCount(DSL.table("registration_reservation"))).isEqualTo(1);
    assertThat(outboxStore.eraseExpiredSensitive(now.plusSeconds(599), 128)).isZero();
    assertThat(outboxStore.eraseExpiredSensitive(now.plusSeconds(601), 128)).isEqualTo(1);
    var retainedOutbox =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT state,payload_nonce,payload_ciphertext FROM identity_notification_outbox WHERE outbox_id=?",
                outbox));
    assertThat(retainedOutbox.get("state", String.class)).isEqualTo("FAILED_PERMANENT");
    assertThat(retainedOutbox.get("payload_nonce")).isNull();
    assertThat(retainedOutbox.get("payload_ciphertext")).isNull();
    assertThat(store.deleteDedupBefore(now, 128)).isZero();
    assertThat(store.deleteDedupBefore(now.plusSeconds(1), 128)).isEqualTo(1);
    assertThat(store.findDedup(dedupRequest)).isEmpty();
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT status FROM identity_user WHERE user_id=?", user))
                .get("status", String.class))
        .isEqualTo("PENDING");
    tx.required(
        () -> {
          store.lockContactKey(contact);
          store.confirm(user, contactId, challenge, now.plusSeconds(30));
          return null;
        });
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne("SELECT status FROM identity_user WHERE user_id=?", user))
                .get("status", String.class))
        .isEqualTo("ACTIVE");
    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT verified_at,primary_active,canonical_value,delivery_value FROM identity_contact WHERE contact_id=?",
                contactId));
    assertThat(row.get("verified_at")).isNotNull();
    assertThat(row.get("primary_active", Boolean.class)).isTrue();
    assertThat(row.get("canonical_value", String.class)).isEqualTo("person@example.com");
    assertThat(row.get("delivery_value", String.class)).isEqualTo("Person@Example.com");
    assertThat(dsl.fetchCount(DSL.table("registration_reservation"))).isZero();
  }
}
