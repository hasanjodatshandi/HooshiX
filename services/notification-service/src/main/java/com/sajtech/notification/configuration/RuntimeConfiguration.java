package com.sajtech.notification.configuration;

import com.sajtech.notification.application.delivery.service.ProviderAttemptPlanner;
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
import com.sajtech.notification.domain.notification.service.NotificationStateMachine;
import com.sajtech.notification.domain.notification.service.ProviderRetryPolicy;
import com.sajtech.notification.infrastructure.observability.NotificationKeyRingRefresher;
import com.sajtech.notification.infrastructure.persistence.JooqNotificationAcceptanceRepository;
import com.sajtech.notification.infrastructure.persistence.JooqNotificationTemplateCatalog;
import com.sajtech.notification.infrastructure.persistence.JooqTransactionRunner;
import com.sajtech.notification.infrastructure.persistence.PostgresDatabaseTime;
import com.sajtech.notification.infrastructure.security.escrow.AesGcmDeliveryEscrow;
import com.sajtech.notification.infrastructure.security.fingerprint.FileBackedHmacIntentFingerprint;
import com.sajtech.notification.infrastructure.security.keyring.FileBackedKeyRing;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class RuntimeConfiguration {
  @Bean
  DataSource dataSource(
      @Value("${notification.database.url}") String url,
      @Value("${notification.database.username}") String username,
      @Value("${notification.database.password}") String password) {
    return DataSourceBuilder.create().url(url).username(username).password(password).build();
  }

  @Bean
  DSLContext dslContext(DataSource dataSource) {
    return DSL.using(dataSource, org.jooq.SQLDialect.POSTGRES);
  }

  @Bean
  PlatformTransactionManager transactionManager(DataSource dataSource) {
    return new JdbcTransactionManager(dataSource);
  }

  @Bean
  TransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
    return new JooqTransactionRunner(new TransactionTemplate(transactionManager));
  }

  @Bean
  NotificationAcceptanceRepository notificationAcceptanceRepository(DSLContext dsl) {
    return new JooqNotificationAcceptanceRepository(dsl);
  }

  @Bean
  NotificationTemplateCatalog notificationTemplateCatalog(DSLContext dsl) {
    return new JooqNotificationTemplateCatalog(dsl);
  }

  @Bean
  DatabaseTimePort databaseTimePort(DSLContext dsl) {
    return new PostgresDatabaseTime(dsl);
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
      NotificationRecipientCanonicalizer recipients,
      NotificationLocaleNormalizer locales,
      @Value("${notification.caller-service}") String callerService) {
    return new NotificationIntentFactory(callerService, recipients, locales);
  }

  @Bean
  FingerprintMaterialEncoder fingerprintMaterialEncoder() {
    return new FingerprintMaterialEncoder();
  }

  @Bean
  FileBackedKeyRing fileBackedKeyRing(
      @Value("${notification.key-ring.directory}") java.nio.file.Path keyRingDirectory,
      @Value("${notification.key-ring.max-staleness}") java.time.Duration maxStaleness) {
    return new FileBackedKeyRing(keyRingDirectory, maxStaleness);
  }

  @Bean
  IntentFingerprintPort intentFingerprintPort(FileBackedKeyRing keyRing) {
    return new FileBackedHmacIntentFingerprint(keyRing);
  }

  @Bean
  DeliveryEscrowPort deliveryEscrowPort(FileBackedKeyRing keyRing) {
    return new AesGcmDeliveryEscrow(keyRing);
  }

  @Bean
  BoundedTemplateRenderer boundedTemplateRenderer() {
    return new BoundedTemplateRenderer();
  }

  @Bean
  SubmitNotification submitNotification(
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
  ProviderRetryPolicy providerRetryPolicy() {
    return new ProviderRetryPolicy();
  }

  @Bean
  ProviderAttemptPlanner providerAttemptPlanner(ProviderRetryPolicy retryPolicy) {
    return new ProviderAttemptPlanner(retryPolicy);
  }

  @Bean
  NotificationStateMachine notificationStateMachine() {
    return new NotificationStateMachine();
  }

  @Bean
  NotificationKeyRingRefresher notificationKeyRingRefresher(
      FileBackedKeyRing keyRing,
      MeterRegistry meterRegistry,
      @Value("${notification.key-ring.refresh-interval}") java.time.Duration refreshInterval) {
    return new NotificationKeyRingRefresher(keyRing, meterRegistry, refreshInterval);
  }

  @Bean(name = "notificationKeyRing")
  HealthIndicator notificationKeyRingHealthIndicator(FileBackedKeyRing keyRing) {
    return () ->
        keyRing.isFresh()
            ? org.springframework.boot.actuate.health.Health.up().build()
            : org.springframework.boot.actuate.health.Health.down()
                .withDetail("reason", "key-ring-stale")
                .build();
  }
}
