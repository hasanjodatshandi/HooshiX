package com.sajtech.notification.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.delivery.model.ProviderDispatchOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationOutcome;
import com.sajtech.notification.application.delivery.model.ProviderReconciliationStatus;
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
import com.sajtech.notification.domain.notification.model.ProviderAttemptClassification;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrow;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrowReader;
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
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class NotificationPersistenceIntegrationTest {
  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");

  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(POSTGRES_IMAGE)
          .withDatabaseName("notification")
          .withUsername("notification_test")
          .withPassword("notification_test_password");

  @TempDir Path tempDirectory;

  private DataSource dataSource;
  private DSLContext dsl;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

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
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT recipient_ciphertext FROM notification WHERE notification_id = ?",
                    first.notificationId()))
            .get("recipient_ciphertext", byte[].class);
    byte[] encryptedText =
        Objects.requireNonNull(
                dsl.fetchOne(
                    "SELECT text_ciphertext FROM notification WHERE notification_id = ?",
                    first.notificationId()))
            .get("text_ciphertext", byte[].class);
    assertThat(
            containsSubsequence(
                encryptedRecipient, "person@example.com".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
    assertThat(containsSubsequence(encryptedText, "12345678".getBytes(StandardCharsets.UTF_8)))
        .isFalse();
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

  @Test
  void migrationRejectsCrossDefinitionActivationAndInvalidChannelSemanticState() throws Exception {
    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE notification_template_activation SET active_version_id = ? WHERE definition_id = ?",
                    UUID.fromString("22222222-2222-4222-8222-222222222203"),
                    UUID.fromString("11111111-1111-4111-8111-111111111101")))
        .isInstanceOf(DataAccessException.class);

    SubmitNotificationUseCase useCase = createUseCase();
    Instant messageNotAfter = new PostgresDatabaseTime(dsl).now().plus(Duration.ofMinutes(10));
    var accepted =
        useCase.submit(
            command(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440001"),
                "12345678",
                messageNotAfter));

    assertThatThrownBy(
            () ->
                dsl.execute(
                    "UPDATE notification SET channel = 'SMS', semantic_type = 'PASSWORD_CHANGED_NOTICE' WHERE notification_id = ?",
                    accepted.notificationId()))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void deliveryClaimIsSingleOwnerAndTerminalDeliveryErasesEscrowAndCreatesResultOutbox()
      throws Exception {
    SubmitNotificationUseCase useCase = createUseCase();
    Instant deadline = new PostgresDatabaseTime(dsl).now().plus(Duration.ofMinutes(10));
    var accepted = useCase.submit(command(UUID.randomUUID(), "12345678", deadline));
    JooqDeliveryAttemptRepository attempts = new JooqDeliveryAttemptRepository(dsl);

    var claims = attempts.claimDue(25, Duration.ofSeconds(30));
    assertThat(claims).hasSize(1);
    assertThat(attempts.claimDue(25, Duration.ofSeconds(30))).isEmpty();
    var claim = claims.getFirst();
    FileBackedKeyRing deliveryKeys =
        new FileBackedKeyRing(
            tempDirectory.resolve("delivery.properties"),
            "AES",
            32,
            Clock.systemUTC(),
            Duration.ofHours(1));
    var decrypted = new AesGcmDeliveryEscrowReader(deliveryKeys).decrypt(claim.escrow());
    assertThat(decrypted.recipient()).isEqualTo("person@example.com");
    assertThat(decrypted.text()).contains("12345678");

    attempts.recordProviderAccepted(
        claim,
        ProviderDispatchOutcome.live(
            ProviderAttemptClassification.DEFINITIVE_ACCEPTED, "SMTP_250", null),
        Duration.ofSeconds(1));
    dsl.execute(
        "UPDATE notification_attempt SET next_action_at=clock_timestamp() WHERE attempt_id=?",
        claim.attemptId());
    JooqDeliveryReconciliationRepository reconciliation =
        new JooqDeliveryReconciliationRepository(dsl);
    var observation = reconciliation.claimDue(25, Duration.ofSeconds(30));
    assertThat(observation).hasSize(1);
    reconciliation.recordDelivered(
        observation.getFirst(),
        ProviderReconciliationOutcome.live(
            ProviderReconciliationStatus.DELIVERED, "FIXTURE_DELIVERED", null));

    var row =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT lifecycle,recipient_ciphertext,text_ciphertext FROM notification WHERE notification_id=?",
                accepted.notificationId()));
    assertThat(row.get("lifecycle", String.class)).isEqualTo("DELIVERED");
    assertThat(row.get("recipient_ciphertext", byte[].class)).isNull();
    assertThat(row.get("text_ciphertext", byte[].class)).isNull();
    assertThat(
            dsl.fetchCount(
                DSL.table("notification_result_outbox"),
                DSL.field("notification_id").eq(accepted.notificationId())))
        .isEqualTo(1);
  }

  @Test
  void migrationCreatesProviderCorrelationAndPendingCallbackIndexes() {
    var indexes =
        dsl.fetch(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename IN ('provider_receipt_evidence', 'notification_result_outbox')")
            .getValues("indexname", String.class);

    assertThat(indexes)
        .contains("provider_receipt_correlation_idx", "notification_result_outbox_pending_idx");
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

  private static boolean containsSubsequence(byte[] value, byte[] sequence) {
    if (sequence.length == 0) {
      return true;
    }
    for (int start = 0; start <= value.length - sequence.length; start++) {
      boolean matched = true;
      for (int index = 0; index < sequence.length; index++) {
        if (value[start + index] != sequence[index]) {
          matched = false;
          break;
        }
      }
      if (matched) {
        return true;
      }
    }
    return false;
  }
}
