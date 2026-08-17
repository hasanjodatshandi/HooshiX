package com.sajtech.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.EncryptedField;
import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.submit.port.out.DuplicateNotificationRequestException;
import com.sajtech.notification.application.submit.service.NotificationIntentFactory;
import com.sajtech.notification.application.submit.service.NotificationLocaleNormalizer;
import com.sajtech.notification.application.submit.service.NotificationRecipientCanonicalizer;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class NotificationPersistenceIntegrationTest {
  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:18.0-alpine")
          .withDatabaseName("notification")
          .withUsername("notification_runtime")
          .withPassword("runtime-test-password");

  @Test
  void flywayCreatesAcceptanceSchemaAndRepositoryEnforcesCallerRequestUniqueness() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();

    var dataSource =
        org.springframework.boot.jdbc.DataSourceBuilder.create()
            .url(POSTGRES.getJdbcUrl())
            .username(POSTGRES.getUsername())
            .password(POSTGRES.getPassword())
            .build();
    var dsl = DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
    var repository = new JooqNotificationAcceptanceRepository(dsl);
    var templateCatalog = new JooqNotificationTemplateCatalog(dsl);

    var template =
        templateCatalog
            .findActive(
                NotificationChannel.EMAIL,
                NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
                "en")
            .orElseThrow();
    assertThat(template.version()).isEqualTo(1);

    UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    var intent =
        new NotificationIntentFactory(
                "identity-service",
                new NotificationRecipientCanonicalizer(),
                new NotificationLocaleNormalizer())
            .create(
                new SubmitNotificationCommand(
                    requestId,
                    NotificationChannel.EMAIL,
                    "person@example.com",
                    "en-US",
                    Instant.parse("2026-08-16T00:10:00Z"),
                    new VerificationCodeContent(
                        NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
                        "12345678",
                        10)));
    var write =
        new AcceptedNotificationWrite(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            intent,
            new FingerprintDigest("hmac-sha256-v1", "fingerprint-key-1", new byte[32]),
            new NotificationTemplateVersion(
                template.templateId(),
                template.channel(),
                template.semanticType(),
                template.locale(),
                template.contentDigest(),
                template.subjectTemplate(),
                template.textTemplate(),
                template.htmlTemplate()),
            encrypted(),
            NotificationLifecycle.ACCEPTED,
            Instant.parse("2026-08-16T00:00:00Z"),
            Instant.parse("2026-08-16T00:02:00Z"),
            Instant.parse("2026-08-17T00:00:00Z"));

    repository.insert(write);

    assertThat(repository.findByCallerAndRequestId("identity-service", requestId))
        .get()
        .extracting(stored -> stored.notificationId())
        .isEqualTo(write.notificationId());
    assertThatThrownBy(() -> repository.insert(write))
        .isInstanceOf(DuplicateNotificationRequestException.class);
    assertThat(
            dsl.fetchValue(
                "select recipient_ciphertext is not null and text_ciphertext is not null from notification_delivery where notification_id = ?",
                Boolean.class,
                write.notificationId()))
        .isTrue();
  }

  private static EncryptedDeliveryPayload encrypted() {
    return new EncryptedDeliveryPayload(
        1,
        "delivery-key-1",
        new EncryptedField(new byte[12], new byte[32]),
        new EncryptedField(new byte[12], new byte[32]),
        new EncryptedField(new byte[12], new byte[32]),
        null);
  }
}
