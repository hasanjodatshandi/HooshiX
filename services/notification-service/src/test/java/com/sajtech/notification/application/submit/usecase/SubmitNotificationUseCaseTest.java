package com.sajtech.notification.application.submit.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.notification.application.submit.NotificationSubmissionError;
import com.sajtech.notification.application.submit.NotificationSubmissionException;
import com.sajtech.notification.application.submit.model.AcceptedNotificationWrite;
import com.sajtech.notification.application.submit.model.EncryptedDeliveryPayload;
import com.sajtech.notification.application.submit.model.EncryptedField;
import com.sajtech.notification.application.submit.model.FingerprintDigest;
import com.sajtech.notification.application.submit.model.StoredAcceptedNotification;
import com.sajtech.notification.application.submit.model.SubmitNotificationCommand;
import com.sajtech.notification.application.submit.model.VerificationCodeContent;
import com.sajtech.notification.application.submit.port.out.DatabaseTimePort;
import com.sajtech.notification.application.submit.port.out.DeliveryEscrowPort;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.application.submit.port.out.NotificationAcceptanceRepository;
import com.sajtech.notification.application.submit.port.out.NotificationTemplateCatalog;
import com.sajtech.notification.application.submit.service.FingerprintMaterialEncoder;
import com.sajtech.notification.application.submit.service.NotificationIntentFactory;
import com.sajtech.notification.application.submit.service.NotificationLocaleNormalizer;
import com.sajtech.notification.application.submit.service.NotificationRecipientCanonicalizer;
import com.sajtech.notification.application.template.model.NotificationTemplateVersion;
import com.sajtech.notification.application.template.service.BoundedTemplateRenderer;
import com.sajtech.notification.domain.notification.model.NotificationChannel;
import com.sajtech.notification.domain.notification.model.NotificationSemanticType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SubmitNotificationUseCaseTest {
  private static final UUID REQUEST_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
  private final MutableDatabaseTime databaseTime =
      new MutableDatabaseTime(Instant.parse("2026-08-16T00:00:00Z"));
  private final InMemoryRepository repository = new InMemoryRepository();
  private final SubmitNotificationUseCase useCase = createUseCase();

  @Test
  void equalReplayReturnsOriginalAcceptedResultBeforeCurrentExpiryEvaluation() {
    var first = useCase.submit(command("12345678"));
    databaseTime.now = Instant.parse("2026-08-17T00:00:00Z");

    var replay = useCase.submit(command("12345678"));

    assertThat(replay.notificationId()).isEqualTo(first.notificationId());
    assertThat(replay.acceptedAt()).isEqualTo(first.acceptedAt());
    assertThat(replay.replay()).isTrue();
    assertThat(databaseTime.reads).isEqualTo(1);
  }

  @Test
  void conflictingReplayFailsBeforeCurrentExpiryEvaluation() {
    useCase.submit(command("12345678"));
    databaseTime.now = Instant.parse("2026-08-17T00:00:00Z");

    assertThatThrownBy(() -> useCase.submit(command("87654321")))
        .isInstanceOf(NotificationSubmissionException.class)
        .extracting(exception -> ((NotificationSubmissionException) exception).error())
        .isEqualTo(NotificationSubmissionError.REQUEST_ID_CONFLICT);
    assertThat(databaseTime.reads).isEqualTo(1);
  }

  private SubmitNotificationUseCase createUseCase() {
    NotificationIntentFactory intentFactory =
        new NotificationIntentFactory(
            "identity-service",
            new NotificationRecipientCanonicalizer(),
            new NotificationLocaleNormalizer());
    return new SubmitNotificationUseCase(
        intentFactory,
        new FingerprintMaterialEncoder(),
        new Sha256Fingerprint(),
        work -> work.get(),
        repository,
        templates(),
        databaseTime,
        new BoundedTemplateRenderer(),
        escrow());
  }

  private SubmitNotificationCommand command(String code) {
    return new SubmitNotificationCommand(
        REQUEST_ID,
        NotificationChannel.EMAIL,
        "person@example.com",
        "en-US",
        Instant.parse("2026-08-16T00:10:00Z"),
        new VerificationCodeContent(
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE, code, 10));
  }

  private static NotificationTemplateCatalog templates() {
    NotificationTemplateVersion version =
        new NotificationTemplateVersion(
            UUID.fromString("22222222-2222-4222-8222-222222222201"),
            NotificationChannel.EMAIL,
            NotificationSemanticType.REGISTRATION_VERIFICATION_CODE,
            "en",
            "a".repeat(64),
            "Verify",
            "Code {code} expires in {expires_minutes} minutes",
            null);
    return (channel, semanticType, locale) -> Optional.of(version);
  }

  private static DeliveryEscrowPort escrow() {
    return (notificationId, intent, template, rendered) ->
        new EncryptedDeliveryPayload(
            1,
            "test-key",
            new EncryptedField(new byte[12], new byte[16]),
            new EncryptedField(new byte[12], new byte[16]),
            new EncryptedField(new byte[12], new byte[16]),
            null);
  }

  private static final class Sha256Fingerprint implements IntentFingerprintPort {
    @Override
    public FingerprintDigest compute(byte[] canonicalMaterial) {
      try {
        return new FingerprintDigest(
            "test-v1", "test-key", MessageDigest.getInstance("SHA-256").digest(canonicalMaterial));
      } catch (NoSuchAlgorithmException impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    @Override
    public boolean constantTimeEquals(byte[] left, byte[] right) {
      return MessageDigest.isEqual(left, right);
    }
  }

  private static final class MutableDatabaseTime implements DatabaseTimePort {
    private Instant now;
    private int reads;

    private MutableDatabaseTime(Instant now) {
      this.now = now;
    }

    @Override
    public Instant now() {
      reads++;
      return now;
    }
  }

  private static final class InMemoryRepository implements NotificationAcceptanceRepository {
    private final Map<String, StoredAcceptedNotification> notifications = new HashMap<>();

    @Override
    public Optional<StoredAcceptedNotification> findByCallerAndRequestId(
        String callerService, UUID requestId) {
      return Optional.ofNullable(notifications.get(callerService + ":" + requestId));
    }

    @Override
    public void insert(AcceptedNotificationWrite write) {
      notifications.put(
          write.intent().callerService() + ":" + write.intent().requestId(),
          new StoredAcceptedNotification(
              write.notificationId(),
              write.fingerprint().value(),
              write.fingerprint().version(),
              write.fingerprint().keyId(),
              write.lifecycle(),
              write.acceptedAt()));
    }
  }
}
