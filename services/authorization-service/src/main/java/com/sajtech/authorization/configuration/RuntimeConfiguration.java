package com.sajtech.authorization.configuration;

import com.sajtech.authorization.application.port.out.*;
import com.sajtech.authorization.infrastructure.erasure.*;
import com.sajtech.authorization.infrastructure.health.AuthorizationReadinessHealthIndicator;
import com.sajtech.authorization.infrastructure.observability.AuthorizationCheckPermissionMetrics;
import com.sajtech.authorization.infrastructure.observability.AuthorizationReservationMonitor;
import com.sajtech.authorization.infrastructure.observability.AuthorizationSecurityMetrics;
import com.sajtech.authorization.infrastructure.observability.ObservedAdminQuota;
import com.sajtech.authorization.infrastructure.persistence.JooqAuthorizationStore;
import com.sajtech.authorization.infrastructure.quota.*;
import com.sajtech.authorization.infrastructure.runtime.grpc.CheckPermissionAdmissionController;
import com.sajtech.authorization.infrastructure.runtime.grpc.CheckPermissionOverloadInterceptor;
import com.sajtech.authorization.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.authorization.infrastructure.security.*;
import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.authorization.interfaces.grpc.*;
import com.sajtech.authorization.interfaces.observability.grpc.AuthorizationObservabilityInterceptor;
import com.sajtech.hooshix.contract.validation.ContractValidationServerInterceptor;
import io.grpc.*;
import java.time.Clock;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("!migration")
@EnableConfigurationProperties(AuthorizationProperties.class)
public class RuntimeConfiguration {
  @Bean
  Clock authorizationClock() {
    return Clock.systemUTC();
  }

  @Bean("authorizationIntentKeys")
  FileBackedKeyRing intentKeys(AuthorizationProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.fingerprintKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean("authorizationQuotaKeys")
  FileBackedKeyRing quotaKeys(AuthorizationProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.quotaKeyRingPath(), "HmacSHA256", 32, c, p.keyRingMaximumStaleness());
  }

  @Bean
  IntentFingerprint intentFingerprint(@Qualifier("authorizationIntentKeys") FileBackedKeyRing k) {
    return new HmacIntentFingerprint(k);
  }

  @Bean
  IdentityJwtVerifier identityJwtVerifier(AuthorizationProperties p, Clock c) {
    return new IdentityJwtVerifier(
        p.identityJwtVerifierBundlePath(), p.identityJwtIssuer(), c, p.keyRingMaximumStaleness());
  }

  @Bean
  SecurityMaterialRefresher securityMaterialRefresher(
      @Qualifier("authorizationIntentKeys") FileBackedKeyRing a,
      @Qualifier("authorizationQuotaKeys") FileBackedKeyRing b,
      IdentityJwtVerifier jwt) {
    return new SecurityMaterialRefresher(a, b, jwt);
  }

  @Bean
  AuthorizationSecurityMetrics authorizationSecurityMetrics(
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new AuthorizationSecurityMetrics(meters);
  }

  @Bean
  AuthorizationReservationMonitor authorizationReservationMonitor(
      DSLContext dsl, AuthorizationSecurityMetrics metrics, Clock clock) {
    return new AuthorizationReservationMonitor(dsl, metrics, clock);
  }

  @Bean
  AuthorizationStore authorizationStore(DSLContext dsl, AuthorizationSecurityMetrics metrics) {
    return new JooqAuthorizationStore(dsl, metrics);
  }

  @Bean
  TransactionTemplate authorizationTransactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  JooqAuthorizationErasureRepository authorizationErasureRepository(DSLContext dsl) {
    return new JooqAuthorizationErasureRepository(dsl);
  }

  @Bean(destroyMethod = "shutdownNow", name = "identityErasureChannel")
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  ManagedChannel identityErasureChannel(
      @Value(
              "${authorization.identity-erasure-target:dns:///identity-service.platform-apps.svc.cluster.local:9090}")
          String target) {
    return io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder.forTarget(target)
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  IdentityErasureTargetClient identityErasureTargetClient(
      @Qualifier("identityErasureChannel") ManagedChannel channel) {
    return new IdentityErasureTargetClient(channel);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  AuthorizationErasureListener authorizationErasureListener(
      JooqAuthorizationErasureRepository repository,
      TransactionTemplate transactions,
      Clock clock) {
    return new AuthorizationErasureListener(repository, transactions, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  AuthorizationErasureWorker authorizationErasureWorker(
      IdentityErasureTargetClient identity,
      JooqAuthorizationErasureRepository repository,
      TransactionTemplate transactions,
      Clock clock,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new AuthorizationErasureWorker(identity, repository, transactions, clock, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "authorization",
      name = "erasure-runtime-enabled",
      havingValue = "true")
  AuthorizationErasureReceiptDispatcher authorizationErasureReceiptDispatcher(
      DSLContext dsl,
      org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka,
      TransactionTemplate transactions,
      Clock clock,
      @Value("${authorization.erasure-receipt-topic:hooshix.identity.erasure.receipt.v1}")
          String topic,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new AuthorizationErasureReceiptDispatcher(
        dsl, kafka, transactions, clock, topic, meters);
  }

  @Bean
  HostTimeHealth authorizationHostTime(AuthorizationProperties p) {
    return new FileHostTimeHealth(p.quota().hostTimeStatusPath());
  }

  @Bean
  ClockSafetyGuard authorizationClockGuard(Clock c) {
    return new ClockSafetyGuard(c);
  }

  @Bean(destroyMethod = "close")
  RedisAdminQuota adminQuota(
      AuthorizationProperties p,
      @Qualifier("authorizationQuotaKeys") FileBackedKeyRing keys,
      ClockSafetyGuard guard,
      HostTimeHealth host) {
    var q = p.quota();
    return new RedisAdminQuota(
        q.redisUri(),
        keys,
        guard,
        host,
        q.maxActiveBuckets(),
        q.maxNewBucketsPerMinute(),
        q.minimumMemoryHeadroomPercent());
  }

  @Bean
  @Primary
  AdminQuota observedAdminQuota(
      RedisAdminQuota quota, io.micrometer.core.instrument.MeterRegistry meters) {
    return new ObservedAdminQuota(quota, meters);
  }

  @Bean
  RedisAdminQuotaCleanupWorker adminQuotaCleanup(
      RedisAdminQuota quota, ClockSafetyGuard guard, HostTimeHealth host, Clock c) {
    return new RedisAdminQuotaCleanupWorker(quota, guard, host, c);
  }

  @Bean
  AuthorizationReadinessHealthIndicator authorizationReadiness(
      AuthorizationProperties p,
      DSLContext dsl,
      @Qualifier("authorizationIntentKeys") FileBackedKeyRing intent,
      @Qualifier("authorizationQuotaKeys") FileBackedKeyRing quotaKeys,
      IdentityJwtVerifier jwt,
      RedisAdminQuota quota,
      ClockSafetyGuard guard,
      HostTimeHealth host) {
    return new AuthorizationReadinessHealthIndicator(
        p, dsl, intent, quotaKeys, jwt, quota, guard, host);
  }

  @Bean
  com.sajtech.authorization.application.AuthorizationService applicationAuthorizationService(
      AuthorizationStore store, AdminQuota quota, IntentFingerprint fp, Clock c) {
    return new com.sajtech.authorization.application.AuthorizationService(store, quota, fp, c);
  }

  @Bean
  AuthorizationGrpcService authorizationGrpcService(
      com.sajtech.authorization.application.AuthorizationService service) {
    return new AuthorizationGrpcService(service);
  }

  @Bean
  JwtActorServerInterceptor jwtActorServerInterceptor(IdentityJwtVerifier jwt) {
    return new JwtActorServerInterceptor(jwt);
  }

  @Bean
  AuthorizationObservabilityInterceptor authorizationObservabilityInterceptor(
      io.opentelemetry.api.OpenTelemetry otel, io.micrometer.core.instrument.MeterRegistry meters) {
    return new AuthorizationObservabilityInterceptor(otel, meters);
  }

  @Bean
  AuthorizationCheckPermissionMetrics authorizationCheckPermissionMetrics(
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new AuthorizationCheckPermissionMetrics(meters);
  }

  @Bean
  CheckPermissionAdmissionController checkPermissionAdmissionController(
      AuthorizationProperties p, AuthorizationCheckPermissionMetrics metrics) {
    var overload = p.checkPermissionOverload();
    return new CheckPermissionAdmissionController(
        overload.globalConcurrency(),
        overload.perCallerConcurrency(),
        overload.globalQueueCapacity(),
        overload.perCallerQueueCapacity(),
        overload.maxCallerBuckets(),
        overload.queueWait(),
        metrics);
  }

  @Bean
  CheckPermissionOverloadInterceptor checkPermissionOverloadInterceptor(
      CheckPermissionAdmissionController admission) {
    return new CheckPermissionOverloadInterceptor(admission);
  }

  @Bean
  ContractValidationServerInterceptor contractValidation(
      io.micrometer.core.instrument.MeterRegistry meters) {
    var rejections = meters.counter("hooshix.contract.validation.rejections");
    return new ContractValidationServerInterceptor(ignored -> rejections.increment());
  }

  @Bean
  GrpcServerLifecycle authorizationGrpcServer(
      AuthorizationProperties p,
      AuthorizationGrpcService service,
      AuthorizationObservabilityInterceptor telemetry,
      JwtActorServerInterceptor jwt,
      CheckPermissionOverloadInterceptor overload,
      ContractValidationServerInterceptor validation,
      @Value("${authorization.grpc-bind-address:0.0.0.0}") String bindAddress) {
    return new GrpcServerLifecycle(
        bindAddress,
        p.grpcPort(),
        p.maxConcurrentCallsPerConnection(),
        p.runtimeEnabled(),
        List.<BindableService>of(service),
        List.of(jwt, overload, validation, telemetry));
  }

  @Bean
  PermissionCatalogLoader permissionCatalogLoader() {
    return new PermissionCatalogLoader();
  }

  @Bean
  ApplicationRunner permissionCatalogProjector(
      PermissionCatalogLoader loader, AuthorizationStore store, Clock clock) {
    return args -> {
      var c = loader.load();
      store.projectPermissionCatalog(c.permissions(), c.version(), clock.instant());
    };
  }
}
