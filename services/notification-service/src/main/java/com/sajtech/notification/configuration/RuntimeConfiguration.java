package com.sajtech.notification.configuration;

import com.sajtech.notification.application.delivery.port.in.*;
import com.sajtech.notification.application.delivery.port.out.*;
import com.sajtech.notification.application.delivery.service.*;
import com.sajtech.notification.application.delivery.usecase.*;
import com.sajtech.notification.application.result.port.out.*;
import com.sajtech.notification.application.submit.port.in.SubmitNotification;
import com.sajtech.notification.application.submit.port.out.DatabaseTimePort;
import com.sajtech.notification.application.submit.port.out.DeliveryEscrowPort;
import com.sajtech.notification.application.submit.port.out.IntentFingerprintPort;
import com.sajtech.notification.application.submit.port.out.NotificationAcceptanceRepository;
import com.sajtech.notification.application.submit.port.out.NotificationTemplateCatalog;
import com.sajtech.notification.application.submit.port.out.TransactionRunner;
import com.sajtech.notification.application.submit.service.FingerprintMaterialEncoder;
import com.sajtech.notification.application.submit.service.NotificationIntentFactory;
import com.sajtech.notification.application.submit.service.NotificationLocaleNormalizer;
import com.sajtech.notification.application.submit.service.NotificationRecipientCanonicalizer;
import com.sajtech.notification.application.submit.usecase.SubmitNotificationUseCase;
import com.sajtech.notification.application.template.service.BoundedTemplateRenderer;
import com.sajtech.notification.application.template.service.TemplateContentDigest;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import com.sajtech.notification.infrastructure.client.identity.GrpcIdentityNotificationResultClient;
import com.sajtech.notification.infrastructure.observability.NotificationDeliveryMetrics;
import com.sajtech.notification.infrastructure.observability.NotificationKeyRingRefresher;
import com.sajtech.notification.infrastructure.observability.NotificationReadinessHealthIndicator;
import com.sajtech.notification.infrastructure.observability.ObservedSubmitNotification;
import com.sajtech.notification.infrastructure.persistence.JooqDeliveryAttemptRepository;
import com.sajtech.notification.infrastructure.persistence.JooqDeliveryReconciliationRepository;
import com.sajtech.notification.infrastructure.persistence.JooqNotificationAcceptanceRepository;
import com.sajtech.notification.infrastructure.persistence.JooqNotificationResultOutboxRepository;
import com.sajtech.notification.infrastructure.persistence.JooqNotificationTemplateCatalog;
import com.sajtech.notification.infrastructure.persistence.PostgresDatabaseTime;
import com.sajtech.notification.infrastructure.persistence.SpringTransactionRunner;
import com.sajtech.notification.infrastructure.provider.FileBackedNotificationProviderConfiguration;
import com.sajtech.notification.infrastructure.provider.NotificationProviderConfiguration;
import com.sajtech.notification.infrastructure.provider.ippanel.IpPanelSmsProviderAdapter;
import com.sajtech.notification.infrastructure.provider.liara.LiaraSmtpProviderAdapter;
import com.sajtech.notification.infrastructure.runtime.delivery.NotificationDeliveryWorker;
import com.sajtech.notification.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.notification.infrastructure.runtime.result.NotificationResultDispatcher;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrow;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrowReader;
import com.sajtech.notification.infrastructure.security.fingerprint.FileBackedHmacIntentFingerprint;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.notification.interfaces.notification.grpc.NotificationAdmissionInterceptor;
import com.sajtech.notification.interfaces.notification.grpc.NotificationGrpcService;
import com.sajtech.notification.interfaces.observability.grpc.SafeTracingServerInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.random.RandomGenerator;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@Profile("!migration")
public class RuntimeConfiguration {
  @Bean
  Clock notificationClock() {
    return Clock.systemUTC();
  }

  @Bean("fingerprintKeyRing")
  FileBackedKeyRing fingerprintKeyRing(NotificationProperties properties, Clock clock) {
    return new FileBackedKeyRing(
        properties.fingerprintKeyRingPath(),
        "HmacSHA256",
        32,
        clock,
        properties.keyRingMaximumStaleness());
  }

  @Bean("deliveryKeyRing")
  FileBackedKeyRing deliveryKeyRing(NotificationProperties properties, Clock clock) {
    return new FileBackedKeyRing(
        properties.deliveryKeyRingPath(), "AES", 32, clock, properties.keyRingMaximumStaleness());
  }

  @Bean
  NotificationKeyRingRefresher notificationKeyRingRefresher(
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing fingerprintKeyRing,
      @Qualifier("deliveryKeyRing") FileBackedKeyRing deliveryKeyRing) {
    return new NotificationKeyRingRefresher(fingerprintKeyRing, deliveryKeyRing);
  }

  @Bean
  IntentFingerprintPort intentFingerprintPort(
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing keyRing) {
    return new FileBackedHmacIntentFingerprint(keyRing);
  }

  @Bean
  DeliveryEscrowPort deliveryEscrowPort(@Qualifier("deliveryKeyRing") FileBackedKeyRing keyRing) {
    return new AesGcmDeliveryEscrow(keyRing, new SecureRandom());
  }

  @Bean
  NotificationAcceptanceRepository notificationAcceptanceRepository(DSLContext dsl) {
    return new JooqNotificationAcceptanceRepository(dsl);
  }

  @Bean
  TemplateContentDigest templateContentDigest() {
    return new TemplateContentDigest();
  }

  @Bean
  NotificationTemplateCatalog notificationTemplateCatalog(
      DSLContext dsl, TemplateContentDigest contentDigest) {
    return new JooqNotificationTemplateCatalog(dsl, contentDigest);
  }

  @Bean
  PostgresDatabaseTime databaseTimePort(DSLContext dsl) {
    return new PostgresDatabaseTime(dsl);
  }

  @Bean
  TransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
    return new SpringTransactionRunner(transactionManager);
  }

  @Bean
  DeliveryEscrowReaderPort deliveryEscrowReaderPort(
      @Qualifier("deliveryKeyRing") FileBackedKeyRing keyRing) {
    return new AesGcmDeliveryEscrowReader(keyRing);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationProviderConfiguration notificationProviderConfiguration(
      NotificationProperties properties) {
    return FileBackedNotificationProviderConfiguration.load(properties.providerConfigurationPath());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationProviderGateway liaraSmtpProviderAdapter(
      NotificationProviderConfiguration configuration) {
    return new LiaraSmtpProviderAdapter(configuration);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationProviderGateway ipPanelSmsProviderAdapter(
      NotificationProviderConfiguration configuration) {
    return new IpPanelSmsProviderAdapter(configuration);
  }

  @Bean
  ProviderRetryPolicy providerRetryPolicy() {
    return new ProviderRetryPolicy();
  }

  @Bean
  ProviderAttemptPlanner providerAttemptPlanner(ProviderRetryPolicy retryPolicy) {
    return new ProviderAttemptPlanner(retryPolicy);
  }

  @Bean
  ProviderObservationPolicy providerObservationPolicy() {
    return new ProviderObservationPolicy();
  }

  @Bean
  DeliveryAttemptRepository deliveryAttemptRepository(DSLContext dsl) {
    return new JooqDeliveryAttemptRepository(dsl);
  }

  @Bean
  DeliveryReconciliationRepository deliveryReconciliationRepository(DSLContext dsl) {
    return new JooqDeliveryReconciliationRepository(dsl);
  }

  @Bean
  NotificationResultOutboxRepository notificationResultOutboxRepository(DSLContext dsl) {
    return new JooqNotificationResultOutboxRepository(dsl);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  RunDeliveryBatch runDeliveryBatch(
      DeliveryAttemptRepository attempts,
      DeliveryEscrowReaderPort escrow,
      DeliveryDatabaseTimePort databaseTime,
      ProviderAttemptPlanner planner,
      ProviderObservationPolicy observationPolicy,
      List<NotificationProviderGateway> providers) {
    return new RunDeliveryBatchService(
        attempts,
        escrow,
        databaseTime,
        planner,
        observationPolicy,
        RandomGenerator.getDefault(),
        providers);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  RunReconciliationBatch runReconciliationBatch(
      DeliveryReconciliationRepository reconciliation,
      DeliveryDatabaseTimePort databaseTime,
      ProviderAttemptPlanner planner,
      ProviderObservationPolicy observationPolicy,
      List<NotificationProviderGateway> providers) {
    return new RunReconciliationBatchService(
        reconciliation, databaseTime, planner, observationPolicy, providers);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationDeliveryMetrics notificationDeliveryMetrics(MeterRegistry meters) {
    return new NotificationDeliveryMetrics(meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationDeliveryWorker notificationDeliveryWorker(
      RunDeliveryBatch delivery,
      RunReconciliationBatch reconciliation,
      NotificationDeliveryMetrics metrics) {
    return new NotificationDeliveryWorker(delivery, reconciliation, metrics);
  }

  @Bean(destroyMethod = "shutdownNow", name = "identityResultChannel")
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  ManagedChannel identityResultChannel(NotificationProperties properties) {
    return NettyChannelBuilder.forTarget(properties.identityResultTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationResultCallbackPort notificationResultCallbackPort(
      @Qualifier("identityResultChannel") ManagedChannel channel) {
    return new GrpcIdentityNotificationResultClient(channel);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "notification",
      name = "delivery-runtime-enabled",
      havingValue = "true")
  NotificationResultDispatcher notificationResultDispatcher(
      NotificationResultOutboxRepository outbox,
      NotificationResultCallbackPort callback,
      DeliveryDatabaseTimePort databaseTime,
      MeterRegistry meters) {
    return new NotificationResultDispatcher(outbox, callback, databaseTime, meters);
  }

  @Bean
  NotificationRecipientCanonicalizer notificationRecipientCanonicalizer() {
    return new NotificationRecipientCanonicalizer();
  }

  @Bean
  NotificationLocaleNormalizer notificationLocaleNormalizer() {
    return new NotificationLocaleNormalizer();
  }

  @Bean
  NotificationIntentFactory notificationIntentFactory(
      NotificationProperties properties,
      NotificationRecipientCanonicalizer recipients,
      NotificationLocaleNormalizer locales) {
    return new NotificationIntentFactory(properties.callerService(), recipients, locales);
  }

  @Bean
  FingerprintMaterialEncoder fingerprintMaterialEncoder() {
    return new FingerprintMaterialEncoder();
  }

  @Bean
  BoundedTemplateRenderer boundedTemplateRenderer() {
    return new BoundedTemplateRenderer();
  }

  @Bean("submitNotificationCore")
  SubmitNotification submitNotificationCore(
      NotificationIntentFactory intentFactory,
      FingerprintMaterialEncoder fingerprintEncoder,
      IntentFingerprintPort fingerprintPort,
      TransactionRunner transactions,
      NotificationAcceptanceRepository notifications,
      NotificationTemplateCatalog templates,
      DatabaseTimePort databaseTime,
      BoundedTemplateRenderer renderer,
      DeliveryEscrowPort deliveryEscrow) {
    return new SubmitNotificationUseCase(
        intentFactory,
        fingerprintEncoder,
        fingerprintPort,
        transactions,
        notifications,
        templates,
        databaseTime,
        renderer,
        deliveryEscrow);
  }

  @Bean
  @Primary
  SubmitNotification observedSubmitNotification(
      @Qualifier("submitNotificationCore") SubmitNotification delegate,
      ObservationRegistry observations,
      MeterRegistry meters) {
    return new ObservedSubmitNotification(delegate, observations, meters);
  }

  @Bean
  NotificationGrpcService notificationGrpcService(SubmitNotification submitNotification) {
    return new NotificationGrpcService(submitNotification);
  }

  @Bean
  SafeTracingServerInterceptor safeTracingServerInterceptor(OpenTelemetry openTelemetry) {
    return new SafeTracingServerInterceptor(openTelemetry);
  }

  @Bean
  NotificationAdmissionInterceptor notificationAdmissionInterceptor(
      NotificationProperties properties, MeterRegistry meters) {
    return new NotificationAdmissionInterceptor(
        properties.maxConcurrentCallsPerConnection(), meters);
  }

  @Bean
  GrpcServerLifecycle grpcServerLifecycle(
      NotificationProperties properties,
      NotificationGrpcService service,
      SafeTracingServerInterceptor tracingInterceptor,
      NotificationAdmissionInterceptor admissionInterceptor,
      @Value("${notification.grpc-bind-address:0.0.0.0}") String bindAddress) {
    return new GrpcServerLifecycle(
        bindAddress,
        properties.grpcPort(),
        properties.maxConcurrentCallsPerConnection(),
        service,
        tracingInterceptor,
        admissionInterceptor);
  }

  @Bean("notificationReadiness")
  NotificationReadinessHealthIndicator notificationReadinessHealthIndicator(
      DSLContext dsl,
      NotificationTemplateCatalog templates,
      @Qualifier("fingerprintKeyRing") FileBackedKeyRing fingerprintKeyRing,
      @Qualifier("deliveryKeyRing") FileBackedKeyRing deliveryKeyRing) {
    return new NotificationReadinessHealthIndicator(
        dsl, templates, fingerprintKeyRing, deliveryKeyRing);
  }
}
