package com.sajtech.identity.configuration;

import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.*;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.notification.usecase.ReportNotificationResultUseCase;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.PasswordRecoverySecretPort;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.password.usecase.*;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.profile.service.ProfileFingerprintEncoder;
import com.sajtech.identity.application.profile.usecase.ProfileManagementUseCase;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.client.compromisedpassword.GrpcCompromisedPasswordClient;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.security.challenge.HmacChallengeSecret;
import com.sajtech.identity.infrastructure.security.challenge.HmacContactVerificationSecret;
import com.sajtech.identity.infrastructure.security.challenge.HmacPasswordRecoverySecret;
import com.sajtech.identity.infrastructure.security.escrow.AesGcmNotificationEscrow;
import com.sajtech.identity.infrastructure.security.externalidentity.*;
import com.sajtech.identity.infrastructure.security.fingerprint.HmacIntentFingerprint;
import com.sajtech.identity.infrastructure.security.jwt.FileBackedRsaSigningKeyRing;
import com.sajtech.identity.infrastructure.security.jwt.RsaJwtAccessTokenSigner;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.mfa.JcaMfaCryptography;
import com.sajtech.identity.infrastructure.security.password.Argon2idPasswordHasher;
import com.sajtech.identity.infrastructure.security.session.HmacSessionCredential;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jooq.autoconfigure.DefaultConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!migration")
class IdentityCoreRuntimeConfiguration {
  @Bean
  Clock identityClock() {
    return Clock.systemUTC();
  }

  @Bean("fingerprintKeyRing")
  FileBackedKeyRing fingerprintKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.fingerprintKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("challengeKeyRing")
  FileBackedKeyRing challengeKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.challengeKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("handoffKeyRing")
  FileBackedKeyRing handoffKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(p.handoffKeyRingPath(), "AES", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("mfaKeyRing")
  FileBackedKeyRing mfaKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(p.mfaKeyRingPath(), "AES", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("quotaKeyRing")
  FileBackedKeyRing quotaKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.quota().hmacKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("refreshKeyRing")
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  FileBackedKeyRing refreshKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.refreshKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  FileBackedRsaSigningKeyRing jwtSigningKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedRsaSigningKeyRing(
        p.jwt().privateKeyRingPath(),
        p.jwt().publicVerifierBundlePath(),
        c,
        p.keyRingMaximumStaleness());
  }

  @Bean
  IdentityKeyRingRefresher keyRingRefresher(
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing a,
      @Qualifier("challengeKeyRing") FileBackedKeyRing b,
      @Qualifier("handoffKeyRing") FileBackedKeyRing c,
      @Qualifier("quotaKeyRing") FileBackedKeyRing d,
      @Qualifier("mfaKeyRing") FileBackedKeyRing e) {
    return new IdentityKeyRingRefresher(a, b, c, d, e);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityAuthenticationKeyRingRefresher authenticationKeyRingRefresher(
      @Qualifier("refreshKeyRing") FileBackedKeyRing refresh, FileBackedRsaSigningKeyRing signing) {
    return new IdentityAuthenticationKeyRingRefresher(refresh, signing);
  }

  @Bean
  IntentFingerprintPort fingerprint(@Qualifier("fingerprintKeyRing") FileBackedKeyRing keys) {
    return new HmacIntentFingerprint(keys);
  }

  @Bean
  ExternalIdentityFingerprintPort externalIdentityFingerprint(
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing keys) {
    return new HmacExternalIdentityFingerprint(keys);
  }

  @Bean
  ExternalIdentityResultCryptoPort externalIdentityResultCrypto(
      @Qualifier("mfaKeyRing") FileBackedKeyRing keys) {
    return new AesGcmExternalIdentityResultCrypto(keys);
  }

  @Bean("registrationChallengeSecrets")
  ChallengeSecretPort challengeSecrets(@Qualifier("challengeKeyRing") FileBackedKeyRing keys) {
    return new HmacChallengeSecret(keys);
  }

  @Bean("contactChallengeSecrets")
  ChallengeSecretPort contactChallengeSecrets(
      @Qualifier("challengeKeyRing") FileBackedKeyRing keys) {
    return new HmacContactVerificationSecret(keys);
  }

  @Bean
  PasswordRecoverySecretPort passwordRecoverySecrets(
      @Qualifier("challengeKeyRing") FileBackedKeyRing keys) {
    return new HmacPasswordRecoverySecret(keys);
  }

  @Bean
  NotificationEscrowPort escrow(@Qualifier("handoffKeyRing") FileBackedKeyRing keys) {
    return new AesGcmNotificationEscrow(keys);
  }

  @Bean
  TransactionRunner transactions(PlatformTransactionManager tm, DSLContext dsl) {
    return new SpringTransactionRunner(tm, dsl);
  }

  @Bean
  DefaultConfigurationCustomizer identityJooqTimeout() {
    return configuration -> configuration.settings().setQueryTimeout(3);
  }

  @Bean
  RegistrationStore registrationStore(DSLContext dsl) {
    return new JooqRegistrationStore(dsl);
  }

  @Bean
  AuthenticationStore authenticationStore(DSLContext dsl) {
    return new JooqAuthenticationStore(dsl);
  }

  @Bean
  ErasureStore erasureStore(DSLContext dsl) {
    return new JooqErasureStore(dsl);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.application.erasure.port.out.ErasureCommandOutbox erasureCommandOutbox(
      DSLContext dsl) {
    return new JooqErasureCommandOutbox(dsl);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.ErasureCommandOutboxDispatcher
      erasureCommandOutboxDispatcher(
          com.sajtech.identity.application.erasure.port.out.ErasureCommandOutbox outbox,
          org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka,
          TransactionRunner transactions,
          Clock clock,
          @Value("${identity.erasure-command-topic:hooshix.identity.erasure.command.v1}")
              String topic,
          MeterRegistry meters) {
    return new com.sajtech.identity.infrastructure.worker.ErasureCommandOutboxDispatcher(
        outbox, kafka, transactions, clock, topic, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  JooqIdentityErasureParticipant identityErasureParticipant(
      DSLContext dsl, ErasureStore erasureStore) {
    return new JooqIdentityErasureParticipant(dsl, erasureStore);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.IdentityErasureListener identityErasureListener(
      JooqIdentityErasureParticipant repository, TransactionRunner transactions, Clock clock) {
    return new com.sajtech.identity.infrastructure.worker.IdentityErasureListener(
        repository, transactions, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.IdentityErasureWorker identityErasureWorker(
      JooqIdentityErasureParticipant repository,
      TransactionRunner transactions,
      Clock clock,
      MeterRegistry meters) {
    return new com.sajtech.identity.infrastructure.worker.IdentityErasureWorker(
        repository, transactions, clock, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  JooqErasureReceiptCoordinator erasureReceiptCoordinator(DSLContext dsl) {
    return new JooqErasureReceiptCoordinator(dsl);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.ErasureReceiptListener erasureReceiptListener(
      JooqErasureReceiptCoordinator coordinator, TransactionRunner transactions, Clock clock) {
    return new com.sajtech.identity.infrastructure.worker.ErasureReceiptListener(
        coordinator, transactions, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.ErasureReceiptWorker erasureReceiptWorker(
      JooqErasureReceiptCoordinator coordinator,
      TransactionRunner transactions,
      Clock clock,
      MeterRegistry meters) {
    return new com.sajtech.identity.infrastructure.worker.ErasureReceiptWorker(
        coordinator, transactions, clock, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  com.sajtech.identity.infrastructure.worker.IdentityErasureReceiptDispatcher
      identityErasureReceiptDispatcher(
          DSLContext dsl,
          org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka,
          TransactionRunner transactions,
          Clock clock,
          @Value("${identity.erasure-receipt-topic:hooshix.identity.erasure.receipt.v1}")
              String topic,
          MeterRegistry meters) {
    return new com.sajtech.identity.infrastructure.worker.IdentityErasureReceiptDispatcher(
        dsl, kafka, transactions, clock, topic, meters);
  }

  @Bean
  MfaStore mfaStore(DSLContext dsl) {
    return new JooqMfaStore(dsl);
  }

  @Bean
  ExternalIdentityStore externalIdentityStore(DSLContext dsl) {
    return new JooqExternalIdentityStore(dsl);
  }

  @Bean
  MfaCryptographyPort mfaCryptography(
      @Qualifier("mfaKeyRing") FileBackedKeyRing encryption,
      @Qualifier("challengeKeyRing") FileBackedKeyRing digests) {
    return new JcaMfaCryptography(encryption, digests);
  }

  @Bean
  PasswordRecoveryStore passwordRecoveryStore(DSLContext dsl) {
    return new JooqPasswordRecoveryStore(dsl);
  }

  @Bean
  NotificationOutboxStore notificationOutboxStore(DSLContext dsl) {
    return new JooqNotificationOutboxStore(dsl);
  }

  @Bean
  NotificationResultStore notificationResultStore(DSLContext dsl) {
    return new JooqNotificationResultStore(dsl);
  }

  @Bean
  ReportNotificationResult reportNotificationResult(
      NotificationResultStore store, TransactionRunner transactions) {
    return new ReportNotificationResultUseCase(store, transactions);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ProfileManagementUseCase profileCore(
      com.sajtech.identity.application.profile.port.out.ProfileContactStore store,
      AuthenticationStore authenticationStore,
      RefreshCredentialLookup lookup,
      ContactCanonicalizer contacts,
      ProfileCanonicalizer profiles,
      IntentFingerprintPort fingerprints,
      @Qualifier("contactChallengeSecrets") ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner transactions,
      Clock clock) {
    return new ProfileManagementUseCase(
        store,
        authenticationStore,
        lookup,
        contacts,
        profiles,
        new ProfileFingerprintEncoder(),
        fingerprints,
        challenges,
        escrow,
        transactions,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ProfileManagement observedProfile(
      ProfileManagementUseCase core, ObservationRegistry observations, MeterRegistry meters) {
    return new ObservedProfileManagement(core, observations, meters);
  }

  @Bean
  com.sajtech.identity.application.profile.port.out.ProfileContactStore profileContactStore(
      DSLContext dsl) {
    return new com.sajtech.identity.infrastructure.persistence.JooqProfileContactStore(dsl);
  }

  @Bean
  ContactCanonicalizer contacts() {
    return new ContactCanonicalizer();
  }

  @Bean
  ProfileCanonicalizer profiles() {
    return new ProfileCanonicalizer();
  }

  @Bean
  PasswordNormalizer passwords() {
    return new PasswordNormalizer();
  }

  @Bean
  FingerprintMaterialEncoder fingerprintMaterialEncoder() {
    return new FingerprintMaterialEncoder();
  }

  @Bean
  IdempotencyGuard idempotency(IntentFingerprintPort fp) {
    return new IdempotencyGuard(fp);
  }

  @Bean(destroyMethod = "shutdownNow", name = "compromisedPasswordChannel")
  ManagedChannel compromisedPasswordChannel(IdentityProperties p) {
    return NettyChannelBuilder.forTarget(p.compromisedPasswordTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .build();
  }

  @Bean
  CompromisedPasswordPort compromisedPasswordCore(
      @Qualifier("compromisedPasswordChannel") ManagedChannel channel, IdentityProperties p) {
    return new GrpcCompromisedPasswordClient(channel, p.compromisedPasswordMaxInFlight());
  }

  @Bean
  @Primary
  CompromisedPasswordPort compromisedPasswordObserved(
      @Qualifier("compromisedPasswordCore") CompromisedPasswordPort delegate,
      ObservationRegistry observations) {
    return new ObservedCompromisedPassword(delegate, observations);
  }

  @Bean
  Argon2idPasswordHasher passwordHashCore(IdentityProperties p) {
    return new Argon2idPasswordHasher(p.argon2MaxConcurrentHashes());
  }

  @Bean
  @Primary
  PasswordHashPort passwordHashObserved(
      @Qualifier("passwordHashCore") Argon2idPasswordHasher delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedPasswordHash(delegate, observations, meters);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  PasswordVerificationPort passwordVerificationObserved(
      @Qualifier("passwordHashCore") Argon2idPasswordHasher delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedPasswordVerification(delegate, observations, meters);
  }

  @Bean
  HostTimeHealth hostTimeHealth(IdentityProperties p) {
    return new FileHostTimeHealth(p.quota().hostTimeStatusPath());
  }

  @Bean
  ClockSafetyGuard quotaClockGuard(Clock clock) {
    return new ClockSafetyGuard(clock);
  }

  @Bean
  QuotaKeyEncoder quotaKeyEncoder(@Qualifier("quotaKeyRing") FileBackedKeyRing keys) {
    return new QuotaKeyEncoder(keys);
  }

  @Bean(destroyMethod = "close", name = "semanticQuotaCore")
  RedisSemanticQuota semanticQuotaCore(
      IdentityProperties p, QuotaKeyEncoder keys, ClockSafetyGuard guard, HostTimeHealth hostTime) {
    return new RedisSemanticQuota(
        p.quota().redisUri(),
        keys,
        guard,
        hostTime,
        p.quota().maxActiveBuckets(),
        p.quota().maxNewBucketsPerMinute(),
        p.quota().minimumMemoryHeadroomPercent());
  }

  @Bean
  @Primary
  SemanticQuotaPort semanticQuotaObserved(
      @Qualifier("semanticQuotaCore") SemanticQuotaPort delegate,
      ObservationRegistry observations) {
    return new ObservedSemanticQuota(delegate, observations);
  }

  @Bean(destroyMethod = "close", name = "loginQuotaCore")
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  RedisLoginQuota loginQuotaCore(
      IdentityProperties p, QuotaKeyEncoder keys, ClockSafetyGuard guard, HostTimeHealth hostTime) {
    return new RedisLoginQuota(
        p.quota().redisUri(),
        keys,
        guard,
        hostTime,
        p.quota().maxActiveBuckets(),
        p.quota().maxNewBucketsPerMinute(),
        p.quota().minimumMemoryHeadroomPercent());
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  LoginQuotaPort loginQuotaObserved(
      @Qualifier("loginQuotaCore") RedisLoginQuota delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedLoginQuota(delegate, observations, meters);
  }

  @Bean
  RedisQuotaCleanupWorker quotaCleanup(
      @Qualifier("semanticQuotaCore") RedisSemanticQuota quota,
      ClockSafetyGuard guard,
      HostTimeHealth hostTime,
      Clock clock) {
    return new RedisQuotaCleanupWorker(quota, guard, hostTime, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  SessionCredentialPort sessionCredentials(@Qualifier("refreshKeyRing") FileBackedKeyRing keys) {
    return new HmacSessionCredential(keys);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  AccessTokenSigner accessTokenSigner(IdentityProperties p, FileBackedRsaSigningKeyRing keys) {
    return new RsaJwtAccessTokenSigner(p.jwt().issuer(), keys);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  RefreshCredentialLookup refreshCredentialLookup(SessionCredentialPort credentials) {
    return new RefreshCredentialLookup(credentials);
  }
}
