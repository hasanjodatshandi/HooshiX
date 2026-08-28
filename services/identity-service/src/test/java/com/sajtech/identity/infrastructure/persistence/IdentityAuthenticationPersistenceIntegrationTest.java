package com.sajtech.identity.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.RefreshSessionUseCase;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.password.port.out.PreparedPasswordRecovery;
import com.sajtech.identity.application.registration.model.EncryptedHandoff;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.*;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class IdentityAuthenticationPersistenceIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "postgres:18.4-bookworm@sha256:1961f96e6029a02c3812d7cb329a3b03a3ac2bb067058dec17b0f5596aca9296")
          .asCompatibleSubstituteFor("postgres");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("identity")
          .withUsername("identity_test")
          .withPassword("identity_test_password");
  private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
  private DSLContext dsl;
  private JooqAuthenticationStore store;
  private SpringTransactionRunner tx;
  private UUID userId;

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
    store = new JooqAuthenticationStore(dsl);
    tx = new SpringTransactionRunner(new DataSourceTransactionManager(source), dsl);
    userId = UUID.randomUUID();
    insertActiveUser(userId);
  }

  @Test
  void transactionalCredentialLockRejectsContactThatWasRemovedAfterInitialProofLookup() {
    CanonicalContact contact =
        new CanonicalContact(RegistrationChannel.EMAIL, "person@example.com", "Person@Example.com");
    UUID contactId = UUID.randomUUID();
    dsl.execute(
        "INSERT INTO identity_credential(user_id,password_hash,algorithm,created_at,updated_at) VALUES (?,?,'ARGON2ID',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        userId,
        "$argon2id$stored",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    dsl.execute(
        "INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,verified_at,primary_active,created_at,updated_at) VALUES (?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),TRUE,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        contactId,
        userId,
        "EMAIL",
        contact.canonicalValue(),
        contact.deliveryValue(),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));

    assertThat(store.findVerifiedLocalCredential(contact)).isPresent();
    dsl.execute(
        "UPDATE identity_contact SET removed_at=CAST(? AS TIMESTAMP WITH TIME ZONE), primary_active=FALSE, updated_at=CAST(? AS TIMESTAMP WITH TIME ZONE) WHERE contact_id=?",
        OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW.plusSeconds(1), ZoneOffset.UTC),
        contactId);

    assertThat(tx.required(() -> store.lockVerifiedLocalCredential(userId, contact))).isEmpty();
  }

  @Test
  void rotationRetainsPredecessorDigestAndFamilyRevocationRevokesSuccessor() {
    RefreshDigest oldDigest = digest((byte) 1);
    RefreshDigest nextDigest = digest((byte) 2);
    UUID family = UUID.randomUUID();
    PreparedSession prepared = session(family, oldDigest);
    tx.required(
        () -> {
          store.createSession(prepared);
          return null;
        });

    LockedRefreshCredential current =
        tx.required(() -> store.lockRefreshCredential(oldDigest).orElseThrow());
    tx.required(
        () -> {
          LockedRefreshCredential locked = store.lockRefreshCredential(oldDigest).orElseThrow();
          store.rotateRefresh(
              locked,
              UUID.randomUUID(),
              nextDigest,
              NOW.plusSeconds(60),
              NOW.plus(Duration.ofDays(7)));
          return null;
        });

    assertThat(store.lockRefreshCredential(oldDigest).orElseThrow().credentialState())
        .isEqualTo("ROTATED");
    assertThat(store.lockRefreshCredential(nextDigest).orElseThrow().credentialState())
        .isEqualTo("ACTIVE");
    tx.required(
        () -> {
          store.revokeFamily(
              current.refreshFamilyId(),
              RefreshFamilyRevocationReason.REFRESH_REUSE,
              NOW.plusSeconds(61));
          return null;
        });

    assertThat(store.lockRefreshCredential(nextDigest).orElseThrow().credentialState())
        .isEqualTo("REVOKED");
    assertThat(
            Objects.requireNonNull(
                    dsl.fetchOne(
                        "SELECT state,revocation_reason FROM identity_refresh_family WHERE refresh_family_id=?",
                        family))
                .get("revocation_reason", String.class))
        .isEqualTo("REFRESH_REUSE");
    assertThat(
            dsl.fetchCount(
                DSL.table("identity_security_audit"),
                DSL.field("event_code").eq("IDENTITY_REFRESH_REUSE_DETECTED")))
        .isEqualTo(1);
  }

  @Test
  void mfaAssuranceTimeIsPersistedWhenCurrentRefreshRotates() {
    RefreshDigest oldDigest = digest((byte) 91);
    RefreshDigest nextDigest = digest((byte) 92);
    UUID family = UUID.randomUUID();
    tx.required(
        () -> {
          store.createSession(session(family, oldDigest));
          LockedRefreshCredential locked = store.lockRefreshCredential(oldDigest).orElseThrow();
          store.markMfaAuthenticated(family, NOW.plusSeconds(60));
          store.rotateRefresh(
              locked,
              UUID.randomUUID(),
              nextDigest,
              NOW.plusSeconds(60),
              NOW.plus(Duration.ofDays(7)));
          return null;
        });

    LockedRefreshCredential rotated = store.lockRefreshCredential(nextDigest).orElseThrow();
    assertThat(rotated.mfaAuthenticatedAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void simultaneousRefreshSerializesAndSecondPredecessorUseRevokesFamily() throws Exception {
    RefreshDigest oldDigest = digest((byte) 3);
    UUID family = UUID.randomUUID();
    tx.required(
        () -> {
          store.createSession(session(family, oldDigest));
          return null;
        });
    SessionCredentialPort credentials = new FixedSessionCredentials(oldDigest, digest((byte) 4));
    RefreshSessionUseCase useCase =
        new RefreshSessionUseCase(
            new RefreshCredentialLookup(credentials),
            credentials,
            tx,
            store,
            Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));
    CyclicBarrier barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<String> call =
          () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
              useCase.refresh(
                  new RefreshSessionCommand(UUID.randomUUID(), FixedSessionCredentials.OLD));
              return "SUCCESS";
            } catch (AuthenticationException exception) {
              return exception.error().name();
            }
          };
      Future<String> first = executor.submit(call);
      Future<String> second = executor.submit(call);
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("SUCCESS", "REFRESH_REUSE_DETECTED");
    } finally {
      executor.shutdownNow();
    }

    var familyRow =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT state,revocation_reason FROM identity_refresh_family WHERE refresh_family_id=?",
                family));
    assertThat(familyRow.get("state", String.class)).isEqualTo("REVOKED");
    assertThat(familyRow.get("revocation_reason", String.class)).isEqualTo("REFRESH_REUSE");
  }

  @Test
  void cleanupKeepsFamilyUntilThirtyFiveDaysAfterAbsoluteExpiryAndPreservesAudit() {
    UUID family = UUID.randomUUID();
    tx.required(
        () -> {
          store.createSession(session(family, digest((byte) 5)));
          return null;
        });
    Instant absolute = NOW.plus(Duration.ofDays(30));

    assertThat(store.deleteFamiliesBefore(absolute, 128)).isZero();
    assertThat(store.deleteFamiliesBefore(absolute.plusSeconds(1), 128)).isEqualTo(1);
    assertThat(dsl.fetchCount(DSL.table("identity_refresh_family"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("identity_refresh_credential"))).isZero();
    assertThat(dsl.fetchCount(DSL.table("identity_security_audit"))).isEqualTo(1);
  }

  @Test
  void passwordRecoveryChallengeAndNotificationHandoffCommitWithSingleOwner() {
    UUID contactId = UUID.randomUUID();
    CanonicalContact contact =
        new CanonicalContact(RegistrationChannel.EMAIL, "person@example.com", "Person@example.com");
    dsl.execute(
        "INSERT INTO identity_credential(user_id,password_hash,algorithm,created_at,updated_at) VALUES (?,?,'ARGON2ID',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        userId,
        "$argon2id$stored",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    dsl.execute(
        "INSERT INTO identity_contact(contact_id,user_id,contact_type,canonical_value,delivery_value,verified_at,primary_active,created_at,updated_at) VALUES (?,?,?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),TRUE,CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        contactId,
        userId,
        "EMAIL",
        contact.canonicalValue(),
        contact.deliveryValue(),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    UUID requestId = UUID.randomUUID();
    UUID challengeId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    JooqPasswordRecoveryStore recovery = new JooqPasswordRecoveryStore(dsl);

    tx.required(
        () -> {
          recovery.create(
              new PreparedPasswordRecovery(
                  requestId,
                  challengeId,
                  outboxId,
                  UUID.randomUUID(),
                  userId,
                  contactId,
                  contact,
                  RegistrationLocale.FA,
                  new byte[32],
                  "recovery-k1",
                  new EncryptedHandoff("escrow-k1", new byte[12], new byte[32]),
                  NOW,
                  NOW.plus(Duration.ofMinutes(10))));
          return null;
        });

    assertThat(recovery.requestAlreadyAccepted(requestId)).isTrue();
    assertThat(recovery.findTargetByContact(contact)).isPresent();
    var outbox =
        Objects.requireNonNull(
            dsl.fetchOne(
                "SELECT challenge_id,password_recovery_challenge_id,content_type FROM identity_notification_outbox WHERE outbox_id=?",
                outboxId));
    assertThat(outbox.get("challenge_id", UUID.class)).isNull();
    assertThat(outbox.get("password_recovery_challenge_id", UUID.class)).isEqualTo(challengeId);
    assertThat(outbox.get("content_type", String.class)).isEqualTo("PASSWORD_RECOVERY");

    UUID confirmationId = UUID.randomUUID();
    tx.required(
        () -> {
          recovery.markUsed(challengeId, confirmationId, NOW.plusSeconds(1));
          return null;
        });
    assertThat(recovery.confirmationAlreadyCompleted(confirmationId)).isTrue();
    assertThat(recovery.findActiveByContact(contact.canonicalValue(), NOW)).isEmpty();
  }

  @Test
  void mfaTotpTimestepAndRecoveryCodeConsumptionAreAtomicAndNonReplayable() {
    JooqMfaStore mfa = new JooqMfaStore(dsl);
    UUID enrollmentId = UUID.randomUUID();
    insertActiveMfaEnrollment(enrollmentId, 100L);
    List<GeneratedRecoveryCode> original = recoveryCodes((byte) 10);
    tx.required(
        () -> {
          mfa.replaceRecoveryCodes(userId, enrollmentId, original, NOW);
          return null;
        });

    assertThat(mfa.requiresMfa(userId)).isTrue();
    assertThat(mfa.status(userId)).isEqualTo(new MfaStatus(true, 10));
    tx.required(
        () -> {
          mfa.acceptTotp(enrollmentId, 101L, NOW.plusSeconds(30));
          return null;
        });
    assertThatThrownBy(
            () ->
                tx.required(
                    () -> {
                      mfa.acceptTotp(enrollmentId, 101L, NOW.plusSeconds(31));
                      return null;
                    }))
        .isInstanceOfSatisfying(
            MfaException.class,
            exception -> assertThat(exception.error()).isEqualTo(MfaError.REPLAYED_PROOF));

    GeneratedRecoveryCode used = original.getFirst();
    assertThat(
            tx.required(
                () ->
                    mfa.consumeRecoveryCode(
                        userId, enrollmentId, List.of(used.digest()), NOW.plusSeconds(40))))
        .isTrue();
    assertThat(
            tx.required(
                () ->
                    mfa.consumeRecoveryCode(
                        userId, enrollmentId, List.of(used.digest()), NOW.plusSeconds(41))))
        .isFalse();
    assertThat(mfa.status(userId)).isEqualTo(new MfaStatus(true, 9));

    List<GeneratedRecoveryCode> replacement = recoveryCodes((byte) 40);
    tx.required(
        () -> {
          mfa.replaceRecoveryCodes(userId, enrollmentId, replacement, NOW.plusSeconds(50));
          return null;
        });
    assertThat(mfa.status(userId)).isEqualTo(new MfaStatus(true, 10));
    assertThat(
            tx.required(
                () ->
                    mfa.consumeRecoveryCode(
                        userId,
                        enrollmentId,
                        List.of(original.get(1).digest()),
                        NOW.plusSeconds(51))))
        .isFalse();
  }

  @Test
  void newPrimaryProofSupersedesPriorMfaLoginChallengeAndFifthFailureExhaustsIt() {
    JooqMfaStore mfa = new JooqMfaStore(dsl);
    var first = challenge((byte) 1);
    var second = challenge((byte) 2);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();

    tx.required(
        () -> {
          mfa.replaceLoginChallenge(firstId, userId, first, NOW, NOW.plus(Duration.ofMinutes(5)));
          mfa.replaceLoginChallenge(
              secondId, userId, second, NOW.plusSeconds(1), NOW.plus(Duration.ofMinutes(5)));
          return null;
        });

    assertThat(mfa.findLoginChallenge(List.of(first.digest())).orElseThrow().state())
        .isEqualTo("SUPERSEDED");
    assertThat(mfa.findLoginChallenge(List.of(second.digest())).orElseThrow().state())
        .isEqualTo("ACTIVE");
    tx.required(
        () -> {
          mfa.recordLoginFailure(secondId, 5, NOW.plusSeconds(2));
          return null;
        });
    assertThat(mfa.findLoginChallenge(List.of(second.digest())).orElseThrow().state())
        .isEqualTo("EXHAUSTED");
  }

  @Test
  void mfaRetentionErasesExpiredPendingSecretsAndBoundsChallengeEvidenceCleanup() {
    JooqMfaStore mfa = new JooqMfaStore(dsl);
    GeneratedMfaChallenge pendingChallenge = challenge((byte) 3);
    GeneratedMfaChallenge loginChallenge = challenge((byte) 4);
    UUID pendingId = UUID.randomUUID();
    UUID loginId = UUID.randomUUID();
    Instant expiresAt = NOW.plus(Duration.ofMinutes(5));

    tx.required(
        () -> {
          mfa.replacePendingEnrollment(
              new com.sajtech.identity.application.mfa.port.out.MfaStore.PreparedPendingEnrollment(
                  pendingId,
                  userId,
                  null,
                  pendingChallenge,
                  new EncryptedTotpSecret("mfa-k1", new byte[12], new byte[48]),
                  null,
                  expiresAt,
                  NOW),
              NOW);
          mfa.replaceLoginChallenge(loginId, userId, loginChallenge, NOW, expiresAt);
          return null;
        });

    assertThat(mfa.deletePendingEnrollmentsBefore(expiresAt.minusNanos(1_000), 128)).isZero();
    assertThat(mfa.deletePendingEnrollmentsBefore(expiresAt, 128)).isEqualTo(1);
    assertThat(mfa.lockPendingEnrollment(List.of(pendingChallenge.digest()))).isEmpty();
    assertThat(mfa.deleteLoginChallengesBefore(expiresAt, 128)).isZero();
    assertThat(mfa.deleteLoginChallengesBefore(expiresAt.plusNanos(1_000), 128)).isEqualTo(1);
    assertThat(mfa.findLoginChallenge(List.of(loginChallenge.digest()))).isEmpty();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> mfa.deletePendingEnrollmentsBefore(expiresAt, 257));
  }

  private PreparedSession session(UUID family, RefreshDigest digest) {
    return new PreparedSession(
        family,
        "s".repeat(43),
        userId,
        UUID.randomUUID(),
        digest,
        NOW,
        NOW,
        NOW.plus(Duration.ofDays(7)),
        NOW.plus(Duration.ofDays(30)));
  }

  private static RefreshDigest digest(byte marker) {
    byte[] value = new byte[32];
    value[0] = marker;
    return new RefreshDigest("k1", "refresh-hmac-v1", value);
  }

  private void insertActiveUser(UUID user) {
    dsl.execute(
        "INSERT INTO identity_user(user_id,status,created_at,updated_at) VALUES (?,'ACTIVE',CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))",
        user,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private void insertActiveMfaEnrollment(UUID enrollmentId, long timestep) {
    dsl.execute(
        """
        INSERT INTO identity_totp_enrollment(
          enrollment_id,user_id,state,secret_key_id,secret_version,secret_nonce,
          secret_ciphertext,last_accepted_timestep,activated_at,created_at,updated_at)
        VALUES (?,?,'ACTIVE','mfa-k1','mfa-aes-gcm-v1',?,?,?,CAST(? AS TIMESTAMP WITH TIME ZONE),
                CAST(? AS TIMESTAMP WITH TIME ZONE),CAST(? AS TIMESTAMP WITH TIME ZONE))
        """,
        enrollmentId,
        userId,
        new byte[12],
        new byte[48],
        timestep,
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  private static List<GeneratedRecoveryCode> recoveryCodes(byte start) {
    return java.util.stream.IntStream.range(0, 10)
        .mapToObj(
            index -> {
              byte[] digest = new byte[32];
              digest[0] = (byte) (start + index);
              return new GeneratedRecoveryCode(
                  "AAAA-BBBB-CCCC-" + "ABCDEFGHJK".charAt(index) + "DDD",
                  new MfaDigest(digest, "mfa-k1", "mfa-recovery-hmac-v1"));
            })
        .toList();
  }

  private static GeneratedMfaChallenge challenge(byte marker) {
    byte[] digest = new byte[32];
    digest[0] = marker;
    return new GeneratedMfaChallenge(
        (marker == 1 ? "A" : "B").repeat(43),
        new MfaDigest(digest, "mfa-k1", "mfa-challenge-hmac-v1"));
  }

  private static final class FixedSessionCredentials implements SessionCredentialPort {
    private static final String OLD = "o".repeat(43);
    private static final String NEXT = "n".repeat(43);
    private final RefreshDigest oldDigest;
    private final RefreshDigest nextDigest;

    private FixedSessionCredentials(RefreshDigest oldDigest, RefreshDigest nextDigest) {
      this.oldDigest = oldDigest;
      this.nextDigest = nextDigest;
    }

    @Override
    public String newSessionId() {
      throw new UnsupportedOperationException();
    }

    @Override
    public GeneratedRefreshCredential newRefreshCredential() {
      return new GeneratedRefreshCredential(NEXT, nextDigest);
    }

    @Override
    public List<RefreshDigest> digestCandidates(String encodedCredential) {
      if (!OLD.equals(encodedCredential)) throw new IllegalArgumentException();
      return List.of(oldDigest);
    }
  }
}
