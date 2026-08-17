package com.sajtech.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.submit.service.FingerprintMaterialEncoder;
import com.sajtech.notification.application.submit.service.NotificationIntentFactory;
import com.sajtech.notification.application.submit.service.NotificationLocaleNormalizer;
import com.sajtech.notification.application.submit.service.NotificationRecipientCanonicalizer;
import com.sajtech.notification.application.submit.usecase.SubmitNotificationUseCase;
import com.sajtech.notification.application.template.service.BoundedTemplateRenderer;
import com.sajtech.notification.application.template.service.TemplateContentDigest;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationLifecycle;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrow;
import com.sajtech.notification.infrastructure.security.fingerprint.FileBackedHmacIntentFingerprint;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@Testcontainers
class NotificationPersistenceIntegrationTest {
  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(POSTGRES_IMAGE)
          .withDatabaseName("notification")
          .withUsername("notification_test")
          .withPassword("notification_test_password");

  @TempDir Path tempDirectory;

  private DataSource dataSource;
  private DSLContext dsl;

  @BeforeEach
  void resetDatabase() {
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
    dataSource = source;
    dsl = DSL.using(new TransactionAwareDataSourceProxy(source), SQLDialect.POSTGRES);
  }

  @Test
  void acceptedHandoffIsAtomicIdempotentAndSensitiveContentIsEncryptedAtRest() throws Exception {
    SubmitNotificationUseCase useCase = createUseCase();
    PostgresDatabaseTime databaseTime = new PostgresDatabaseTime(dsl);
    Instant messageNotAfter = databaseTime.now().plus(Duration.ofMinutes(10));
    UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    SubmitNotificationCommand command = command(requestId, "12345678", messageNotAfter);

    var first = useCase.submit(command);
    var replay = useCase.submit(command);

    assertThat(first.lifecycle()).isEqualTo(NotificationLifecycle.ACCEPTED);
    assertThat(replay.notificationId()).isEqualTo(first.notificationId());
    assertThat(replay.replay()).isTrue();
    assertThat(dsl.fetchCount(DSL.table("notification"))).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("notification_attempt"))).isEqualTo(1);

    byte[] encryptedRecipient =
        dsl.fetchOne(
                "SELECT recipient_ciphertext FROM notification WHERE notification_id = ?",
                first.notificationId())
            .get("recipient_ciphertext", byte[].class);
    byte[] encryptedText =
        dsl.fetchOne(
                "SELECT text_ciphertext FROM notification WHERE notification_id = ?",
                first.notificationId())
            .get("text_ciphertext", byte[].class);
    assertThat(encryptedRecipient)
        .doesNotContain("person@example.com".getBytes(StandardCharsets.UTF_8));
    assertThat(encryptedText).doesNotContain("12345678".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void conflictingRequestIdIsRejectedWithoutCreatingSecondNotification() throws Exception {
    SubmitNotificationUseCase useCase = createUseCase();
    Instant messageNotAfter = new PostgresDatabaseTime(dsl).now().plus(Duration.ofMinutes(10));
    UUID requestId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    useCase.submit(command(requestId, "12345678", messageNotAfter));

    assertThatThrownBy(() -> useCase.submit(command(requestId, "87654321", messageNotAfter)))
        .isInstanceOf(NotificationSubmissionException.class)
        .extracting(exception -> ((NotificationSubmissionException) exception).error())
        .isEqualTo(NotificationSubmissionError.REQUEST_ID_CONFLICT);
    assertThat(dsl.fetchCount(DSL.table("notification"))).isEqualTo(1);
  }

  private SubmitNotificationUseCase createUseCase() throws Exception {
    Path fingerprintPath = tempDirectory.resolve("fingerprint.properties");
    Path deliveryPath = tempDirectory.resolve("delivery.properties");
    writeKeyRing(fingerprintPath, (byte) 7);
    writeKeyRing(deliveryPath, (byte) 11);
    Clock clock = Clock.systemUTC();
    FileBackedKeyRing fingerprintKeys =
        new FileBackedKeyRing(fingerprintPath, "HmacSHA256", 32, clock, Duration.ofHours(1));
    FileBackedKeyRing deliveryKeys =
        new FileBackedKeyRing(deliveryPath, "AES", 32, clock, Duration.ofHours(1));
    NotificationIntentFactory intentFactory =
        new NotificationIntentFactory(
            "identity-service",
            new NotificationRecipientCanonicalizer(),
            new NotificationLocaleNormalizer());
    return new SubmitNotificationUseCase(
        intentFactory,
        new FingerprintMaterialEncoder(),
        new FileBackedHmacIntentFingerprint(fingerprintKeys),
        new SpringTransactionRunner(new DataSourceTransactionManager(dataSource)),
        new JooqNotificationAcceptanceRepository(dsl),
        new JooqNotificationTemplateCatalog(dsl, new TemplateContentDigest()),
        new PostgresDatabaseTime(dsl),
        new BoundedTemplateRenderer(),
        new AesGcmDeliveryEscrow(deliveryKeys, new SecureRandom()));
  }

  private static SubmitNotificationCommand command(
      UUID requestId, String code, Instant messageNotAfter) {
    return new SubmitNotificationCommand(
        requestId,
        NotificationChannel.EMAIL,
        "person@example.com",
        "en-US",
        messageNotAfter,
        new VerificationCodeContent(
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, code, 10));
  }

  private static void writeKeyRing(Path path, byte fill) throws Exception {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, fill);
    Files.writeString(
        path,
        "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n",
        StandardCharsets.UTF_8);
  }
}
