package com.sajtech.identity.configuration;

import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.infrastructure.client.compromisedpassword.GrpcCompromisedPasswordClient;
import com.sajtech.identity.infrastructure.client.notification.GrpcNotificationSubmissionClient;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.identity.infrastructure.security.challenge.HmacChallengeSecret;
import com.sajtech.identity.infrastructure.security.escrow.AesGcmNotificationEscrow;
import com.sajtech.identity.infrastructure.security.fingerprint.HmacIntentFingerprint;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.password.Argon2idPasswordHasher;
import com.sajtech.identity.infrastructure.worker.IdentityRetentionWorker;
import com.sajtech.identity.infrastructure.worker.NotificationOutboxDispatcher;
import com.sajtech.identity.interfaces.observability.grpc.SafeTracingServerInterceptor;
import com.sajtech.identity.interfaces.registration.grpc.IdentityRegistrationGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!migration")
public class RuntimeConfiguration {
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

  @Bean("quotaKeyRing")
  FileBackedKeyRing quotaKeyRing(IdentityProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.quota().hmacKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean
  IdentityKeyRingRefresher keyRingRefresher(
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing a,
      @Qualifier("challengeKeyRing") FileBackedKeyRing b,
      @Qualifier("handoffKeyRing") FileBackedKeyRing c,
      @Qualifier("quotaKeyRing") FileBackedKeyRing d) {
    return new IdentityKeyRingRefresher(a, b, c, d);
  }

  @Bean
  IntentFingerprintPort fingerprint(@Qualifier("fingerprintKeyRing") FileBackedKeyRing keys) {
    return new HmacIntentFingerprint(keys);
  }

  @Bean
  ChallengeSecretPort challengeSecrets(@Qualifier("challengeKeyRing") FileBackedKeyRing keys) {
    return new HmacChallengeSecret(keys);
  }

  @Bean
  NotificationEscrowPort escrow(@Qualifier("handoffKeyRing") FileBackedKeyRing keys) {
    return new AesGcmNotificationEscrow(keys);
  }

  @Bean
  TransactionRunner transactions(PlatformTransactionManager tm) {
    return new SpringTransactionRunner(tm);
  }

  @Bean
  RegistrationStore registrationStore(DSLContext dsl) {
    return new JooqRegistrationStore(dsl);
  }

  @Bean
  NotificationOutboxStore notificationOutboxStore(DSLContext dsl) {
    return new JooqNotificationOutboxStore(dsl);
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
  PasswordHashPort passwordHashCore(IdentityProperties p) {
    return new Argon2idPasswordHasher(p.argon2MaxConcurrentHashes());
  }

  @Bean
  @Primary
  PasswordHashPort passwordHashObserved(
      @Qualifier("passwordHashCore") PasswordHashPort delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedPasswordHash(delegate, observations, meters);
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

  @Bean
  RedisQuotaCleanupWorker quotaCleanup(
      @Qualifier("semanticQuotaCore") RedisSemanticQuota quota,
      ClockSafetyGuard guard,
      HostTimeHealth hostTime,
      Clock clock) {
    return new RedisQuotaCleanupWorker(quota, guard, hostTime, clock);
  }

  @Bean
  RegisterLocalUseCase registerCore(
      IdentityProperties p,
      ContactCanonicalizer contacts,
      ProfileCanonicalizer profiles,
      PasswordNormalizer passwords,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      CompromisedPasswordPort compromised,
      PasswordHashPort hashes,
      ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new RegisterLocalUseCase(
        p.phoneRegistrationEnabled(),
        contacts,
        profiles,
        passwords,
        encoder,
        fingerprints,
        idempotency,
        quota,
        compromised,
        hashes,
        challenges,
        escrow,
        tx,
        store,
        clock);
  }

  @Bean
  ResendRegistrationVerificationUseCase resendCore(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      ChallengeSecretPort challenges,
      NotificationEscrowPort escrow,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new ResendRegistrationVerificationUseCase(
        contacts, encoder, fingerprints, idempotency, quota, challenges, escrow, tx, store, clock);
  }

  @Bean
  ConfirmRegistrationUseCase confirmCore(
      ContactCanonicalizer contacts,
      FingerprintMaterialEncoder encoder,
      IntentFingerprintPort fingerprints,
      IdempotencyGuard idempotency,
      SemanticQuotaPort quota,
      ChallengeSecretPort challenges,
      TransactionRunner tx,
      RegistrationStore store,
      Clock clock) {
    return new ConfirmRegistrationUseCase(
        contacts, encoder, fingerprints, idempotency, quota, challenges, tx, store, clock);
  }

  @Bean
  @Primary
  ObservedRegistration observedRegistration(
      RegisterLocalUseCase register,
      ResendRegistrationVerificationUseCase resend,
      ConfirmRegistrationUseCase confirm,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedRegistration(register, resend, confirm, observations, meters);
  }

  @Bean
  IdentityRegistrationGrpcService registrationGrpc(ObservedRegistration observed) {
    return new IdentityRegistrationGrpcService(observed, observed, observed);
  }

  @Bean
  SafeTracingServerInterceptor tracing(OpenTelemetry otel) {
    return new SafeTracingServerInterceptor(otel);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "registration-runtime-enabled",
      havingValue = "true")
  GrpcServerLifecycle grpcLifecycle(
      IdentityProperties p,
      IdentityRegistrationGrpcService service,
      SafeTracingServerInterceptor tracing) {
    return new GrpcServerLifecycle(
        p.grpcPort(), p.maxConcurrentCallsPerConnection(), service, tracing);
  }

  @Bean(destroyMethod = "shutdownNow", name = "notificationChannel")
  ManagedChannel notificationChannel(IdentityProperties p) {
    return NettyChannelBuilder.forTarget(p.notificationTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .build();
  }

  @Bean
  NotificationSubmissionPort notificationSubmission(
      @Qualifier("notificationChannel") ManagedChannel channel) {
    return new GrpcNotificationSubmissionClient(channel);
  }

  @Bean
  IdentityRetentionWorker retentionWorker(
      NotificationOutboxStore outboxStore, RegistrationStore registrationStore, Clock clock) {
    return new IdentityRetentionWorker(outboxStore, registrationStore, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "notification-dispatch-enabled",
      havingValue = "true",
      matchIfMissing = true)
  NotificationOutboxDispatcher notificationDispatcher(
      NotificationOutboxStore store,
      NotificationEscrowPort escrow,
      NotificationSubmissionPort notification,
      Clock clock) {
    return new NotificationOutboxDispatcher(store, escrow, notification, clock);
  }

  @Bean("identityReadiness")
  IdentityReadinessHealthIndicator readiness(
      DSLContext dsl,
      @Qualifier("semanticQuotaCore") RedisSemanticQuota quota,
      HostTimeHealth hostTime,
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing a,
      @Qualifier("challengeKeyRing") FileBackedKeyRing b,
      @Qualifier("handoffKeyRing") FileBackedKeyRing c,
      @Qualifier("quotaKeyRing") FileBackedKeyRing d) {
    return new IdentityReadinessHealthIndicator(dsl, quota, hostTime, a, b, c, d);
  }
}
