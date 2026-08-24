package com.sajtech.identity.configuration;

import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.notification.usecase.ReportNotificationResultUseCase;
import com.sajtech.identity.application.profile.port.in.ProfileManagement;
import com.sajtech.identity.application.profile.usecase.ProfileManagementUseCase;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.client.compromisedpassword.GrpcCompromisedPasswordClient;
import com.sajtech.identity.infrastructure.client.notification.GrpcNotificationSubmissionClient;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.identity.infrastructure.security.challenge.HmacChallengeSecret;
import com.sajtech.identity.infrastructure.security.escrow.AesGcmNotificationEscrow;
import com.sajtech.identity.infrastructure.security.fingerprint.HmacIntentFingerprint;
import com.sajtech.identity.infrastructure.security.jwt.FileBackedRsaSigningKeyRing;
import com.sajtech.identity.infrastructure.security.jwt.RsaJwtAccessTokenSigner;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.security.password.Argon2idPasswordHasher;
import com.sajtech.identity.infrastructure.security.session.HmacSessionCredential;
import com.sajtech.identity.infrastructure.worker.IdentityRetentionWorker;
import com.sajtech.identity.infrastructure.worker.NotificationOutboxDispatcher;
import com.sajtech.identity.interfaces.authentication.grpc.IdentityAuthenticationGrpcService;
import com.sajtech.identity.interfaces.notification.grpc.IdentityNotificationResultGrpcService;
import com.sajtech.identity.interfaces.observability.grpc.IdentityAdmissionInterceptor;
import com.sajtech.identity.interfaces.observability.grpc.SafeTracingServerInterceptor;
import com.sajtech.identity.interfaces.profile.grpc.IdentityProfileGrpcService;
import com.sajtech.identity.interfaces.password.grpc.IdentityPasswordGrpcService;
import com.sajtech.identity.interfaces.registration.grpc.IdentityRegistrationGrpcService;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!migration")
@EnableConfigurationProperties(IdentityProperties.class)
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
      @Qualifier("quotaKeyRing") FileBackedKeyRing d) {
    return new IdentityKeyRingRefresher(a, b, c, d);
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
  AuthenticationStore authenticationStore(DSLContext dsl) {
    return new JooqAuthenticationStore(dsl);
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
  ReportNotificationResult reportNotificationResult(NotificationResultStore store) {
    return new ReportNotificationResultUseCase(store);
  }

  @Bean
  ProfileManagement profileManagement(
      com.sajtech.identity.application.profile.port.out.ProfileContactStore store) {
    return new ProfileManagementUseCase(store);
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
  @ConditionalOnProperty(
      prefix = "identity",
      name = "registration-runtime-enabled",
      havingValue = "true")
  IdentityRegistrationGrpcService registrationGrpc(ObservedRegistration observed) {
    return new IdentityRegistrationGrpcService(observed, observed, observed);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityProfileGrpcService profileGrpc(
      ProfileManagement profileManagement,
      RefreshCredentialLookup lookup,
      AuthenticationStore store) {
    return new IdentityProfileGrpcService(profileManagement, lookup, store);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  AuthenticateLocalUseCase authenticateLocalCore(
      ContactCanonicalizer contacts,
      PasswordNormalizer passwords,
      LoginQuotaPort quota,
      PasswordVerificationPort verifier,
      SessionCredentialPort credentials,
      TransactionRunner tx,
      AuthenticationStore store,
      AuthenticationTenantSelectionPort tenantSelection,
      Clock clock) {
    return new AuthenticateLocalUseCase(
        contacts, passwords, quota, verifier, credentials, tx, store, tenantSelection, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  RefreshSessionUseCase refreshSessionCore(
      RefreshCredentialLookup lookup,
      SessionCredentialPort credentials,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new RefreshSessionUseCase(lookup, credentials, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  LogoutCurrentUseCase logoutCurrentCore(
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new LogoutCurrentUseCase(lookup, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  LogoutAllUseCase logoutAllCore(
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      Clock clock) {
    return new LogoutAllUseCase(lookup, tx, store, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IssueAudienceAccessTokenUseCase issueAudienceAccessTokenCore(
      IdentityProperties p,
      RefreshCredentialLookup lookup,
      TransactionRunner tx,
      AuthenticationStore store,
      com.sajtech.identity.application.authentication.port.out.TenantContextValidationPort tenants,
      AccessTokenSigner signer,
      Clock clock) {
    return new IssueAudienceAccessTokenUseCase(
        p.jwt().allowedAudiences(), lookup, tx, store, tenants, signer, clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedAuthentication observedAuthentication(
      AuthenticateLocalUseCase authenticate,
      RefreshSessionUseCase refresh,
      LogoutCurrentUseCase logoutCurrent,
      LogoutAllUseCase logoutAll,
      IssueAudienceAccessTokenUseCase issueAccessToken,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedAuthentication(
        authenticate, refresh, logoutCurrent, logoutAll, issueAccessToken, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityAuthenticationGrpcService authenticationGrpc(ObservedAuthentication observed) {
    return new IdentityAuthenticationGrpcService(observed, observed, observed, observed, observed);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  IdentityPasswordGrpcService passwordGrpc(
      ChangePassword changePassword,
      RequestPasswordRecovery requestPasswordRecovery,
      ConfirmPasswordRecovery confirmPasswordRecovery) {
    return new IdentityPasswordGrpcService(changePassword, requestPasswordRecovery, confirmPasswordRecovery);
  }

  @Bean
  com.sajtech.identity.infrastructure.persistence.JooqTenantStore tenantStore(
      DSLContext dsl, IntentFingerprintPort fingerprints) {
    return new com.sajtech.identity.infrastructure.persistence.JooqTenantStore(dsl, fingerprints);
  }

  @Bean(destroyMethod = "shutdownNow", name = "authorizationChannel")
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  ManagedChannel authorizationChannel(IdentityProperties p) {
    return NettyChannelBuilder.forTarget(p.authorizationTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .build();
  }

  @Bean
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort authorizationTenantPort(
      @Qualifier("authorizationChannel") ManagedChannel channel) {
    return new com.sajtech.identity.infrastructure.client.authorization
        .GrpcAuthorizationTenantClient(channel);
  }

  @Bean
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  com.sajtech.identity.application.tenant.TenantLifecycleService tenantLifecycleService(
      IdentityProperties p,
      RefreshCredentialLookup lookup,
      SessionCredentialPort credentials,
      AuthenticationStore authenticationStore,
      com.sajtech.identity.infrastructure.persistence.JooqTenantStore tenantStore,
      com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort authorization,
      AccessTokenSigner signer,
      TransactionRunner tx,
      Clock clock) {
    return new com.sajtech.identity.application.tenant.TenantLifecycleService(
        p.jwt().allowedAudiences(),
        lookup,
        credentials,
        authenticationStore,
        tenantStore,
        authorization,
        signer,
        new com.sajtech.identity.application.tenant.service.TenantIntentEncoder(),
        tx,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  ObservedTenantLifecycle observedTenantLifecycle(
      com.sajtech.identity.application.tenant.TenantLifecycleService delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedTenantLifecycle(delegate, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  com.sajtech.identity.interfaces.tenant.grpc.IdentityTenantGrpcService tenantGrpc(
      ObservedTenantLifecycle service) {
    return new com.sajtech.identity.interfaces.tenant.grpc.IdentityTenantGrpcService(service);
  }

  @Bean
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  AuthorizationOutboxMetrics authorizationOutboxMetrics(MeterRegistry meters) {
    return new AuthorizationOutboxMetrics(meters);
  }

  @Bean
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  com.sajtech.identity.infrastructure.worker.AuthorizationOutboxDispatcher
      authorizationOutboxDispatcher(
          com.sajtech.identity.infrastructure.persistence.JooqTenantStore store,
          com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort authorization,
          TransactionRunner tx,
          Clock clock,
          AuthorizationOutboxMetrics metrics) {
    return new com.sajtech.identity.infrastructure.worker.AuthorizationOutboxDispatcher(
        store, store, authorization, tx, clock, metrics);
  }

  @Bean
  SafeTracingServerInterceptor tracing(OpenTelemetry otel) {
    return new SafeTracingServerInterceptor(otel);
  }

  @Bean
  IdentityAdmissionInterceptor identityAdmission(
      IdentityProperties properties, MeterRegistry meters) {
    return new IdentityAdmissionInterceptor(properties.maxGlobalConcurrentCalls(), meters);
  }

  @Bean
  GrpcServerLifecycle grpcLifecycle(
      IdentityProperties p,
      List<BindableService> services,
      SafeTracingServerInterceptor tracing,
      IdentityAdmissionInterceptor admission,
      @Value("${identity.grpc-bind-address:0.0.0.0}") String bindAddress) {
    return new GrpcServerLifecycle(
        bindAddress,
        p.grpcPort(),
        p.maxConcurrentCallsPerConnection(),
        p.registrationRuntimeEnabled()
            || p.authenticationRuntimeEnabled()
            || p.tenantRuntimeEnabled(),
        services,
        tracing,
        admission);
  }

  @Bean(name = "notificationResultGrpcLifecycle")
  @ConditionalOnProperty(
      prefix = "identity",
      name = "notification-result-runtime-enabled",
      havingValue = "true")
  GrpcServerLifecycle notificationResultGrpcLifecycle(
      IdentityProperties p,
      ReportNotificationResult report,
      SafeTracingServerInterceptor tracing,
      IdentityAdmissionInterceptor admission,
      @Value("${identity.notification-result-grpc-bind-address:0.0.0.0}") String bindAddress) {
    return new GrpcServerLifecycle(
        bindAddress,
        p.notificationResultGrpcPort(),
        p.maxConcurrentCallsPerConnection(),
        true,
        List.of(new IdentityNotificationResultGrpcService(report)),
        tracing,
        admission);
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
      NotificationOutboxStore outboxStore,
      RegistrationStore registrationStore,
      AuthenticationStore authenticationStore,
      Clock clock) {
    return new IdentityRetentionWorker(outboxStore, registrationStore, authenticationStore, clock);
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

  @Bean("identityAuthenticationReadiness")
  IdentityAuthenticationReadinessHealthIndicator authenticationReadiness(
      IdentityProperties p,
      @Qualifier("refreshKeyRing") Optional<FileBackedKeyRing> refresh,
      Optional<FileBackedRsaSigningKeyRing> signing,
      @Qualifier("loginQuotaCore") Optional<RedisLoginQuota> loginQuota) {
    return new IdentityAuthenticationReadinessHealthIndicator(
        p.authenticationRuntimeEnabled(),
        () -> refresh.map(FileBackedKeyRing::isFresh).orElse(false),
        () -> signing.map(FileBackedRsaSigningKeyRing::isFresh).orElse(false),
        () -> loginQuota.map(RedisLoginQuota::connectivityHealthy).orElse(false));
  }
}
