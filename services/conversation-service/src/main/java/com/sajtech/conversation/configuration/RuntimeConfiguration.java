package com.sajtech.conversation.configuration;

import com.sajtech.conversation.application.service.ConversationAuthority;
import com.sajtech.conversation.application.service.ConversationService;
import com.sajtech.conversation.application.service.ModelRunWorker;
import com.sajtech.conversation.infrastructure.client.authorization.GrpcPermissionAuthorizer;
import com.sajtech.conversation.infrastructure.erasure.ConversationErasureReceiptDispatcher;
import com.sajtech.conversation.infrastructure.erasure.ConversationErasureWorker;
import com.sajtech.conversation.infrastructure.erasure.IdentityErasureTargetClient;
import com.sajtech.conversation.infrastructure.erasure.JdbcConversationErasureRepository;
import com.sajtech.conversation.infrastructure.health.ConversationReadinessHealthIndicator;
import com.sajtech.conversation.infrastructure.lifecycle.JdbcTenantLifecycleRepository;
import com.sajtech.conversation.infrastructure.model.GitGovernedModelPolicyProvider;
import com.sajtech.conversation.infrastructure.persistence.JdbcConversationRepository;
import com.sajtech.conversation.infrastructure.persistence.JdbcModelRunRepository;
import com.sajtech.conversation.infrastructure.persistence.JdbcModelRunWorkerRepository;
import com.sajtech.conversation.infrastructure.provider.openai.FileBackedOpenAiModelProvider;
import com.sajtech.conversation.infrastructure.runtime.ModelRunWorkerLifecycle;
import com.sajtech.conversation.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifierRefresher;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.keyring.ContentKeyRingRefresher;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import com.sajtech.conversation.interfaces.grpc.BearerTokenServerInterceptor;
import com.sajtech.conversation.interfaces.grpc.ConversationGrpcService;
import com.sajtech.conversation.interfaces.kafka.ConversationErasureListener;
import com.sajtech.conversation.interfaces.kafka.TenantLifecycleListener;
import com.sajtech.conversation.interfaces.observability.grpc.ConversationTracingInterceptor;
import com.sajtech.hooshix.contract.validation.ContractValidationServerInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
@Profile("!migration")
@EnableConfigurationProperties({ConversationProperties.class, ModelWorkerProperties.class})
public class RuntimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  FileBackedContentKeyRing contentKeyRing(ConversationProperties properties, Clock clock) {
    return new FileBackedContentKeyRing(
        properties.contentKeyRingPath(), clock, properties.keyRingMaximumStaleness());
  }

  @Bean
  AesGcmContentCrypto contentCrypto(FileBackedContentKeyRing keyRing) {
    return new AesGcmContentCrypto(keyRing, new SecureRandom());
  }

  @Bean
  ContentKeyRingRefresher contentKeyRingRefresher(FileBackedContentKeyRing keyRing) {
    return new ContentKeyRingRefresher(keyRing);
  }

  @Bean
  IdentityJwtVerifier identityJwtVerifier(ConversationProperties properties, Clock clock) {
    return new IdentityJwtVerifier(
        properties.identityJwtVerifierBundlePath(),
        properties.identityJwtIssuer(),
        clock,
        properties.jwtVerifierMaximumStaleness());
  }

  @Bean
  IdentityJwtVerifierRefresher identityJwtVerifierRefresher(IdentityJwtVerifier verifier) {
    return new IdentityJwtVerifierRefresher(verifier);
  }

  @Bean(name = "conversationAuthorizationChannel", destroyMethod = "shutdownNow")
  ManagedChannel conversationAuthorizationChannel(ConversationProperties properties) {
    return NettyChannelBuilder.forTarget(properties.authorizationTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(16 * 1024)
        .build();
  }

  @Bean
  GrpcPermissionAuthorizer permissionAuthorizer(
      @Qualifier("conversationAuthorizationChannel")
          ManagedChannel conversationAuthorizationChannel,
      ConversationProperties properties) {
    return new GrpcPermissionAuthorizer(
        conversationAuthorizationChannel, properties.authorizationMaximumConcurrentChecks());
  }

  @Bean(name = "conversationIdentityErasureChannel", destroyMethod = "shutdownNow")
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  ManagedChannel conversationIdentityErasureChannel(
      @Value("${conversation.identity-erasure-target}") String target) {
    return NettyChannelBuilder.forTarget(target)
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(32 * 1024)
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  IdentityErasureTargetClient identityErasureTargetClient(
      @Qualifier("conversationIdentityErasureChannel") ManagedChannel channel) {
    return new IdentityErasureTargetClient(channel);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  JdbcConversationErasureRepository conversationErasureRepository(DataSource dataSource) {
    return new JdbcConversationErasureRepository(dataSource);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  ConversationErasureListener conversationErasureListener(
      JdbcConversationErasureRepository repository, Clock clock) {
    return new ConversationErasureListener(repository, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  ConversationErasureWorker conversationErasureWorker(
      IdentityErasureTargetClient identity,
      JdbcConversationErasureRepository repository,
      Clock clock,
      MeterRegistry meters) {
    return new ConversationErasureWorker(identity, repository, clock, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  ConversationErasureReceiptDispatcher conversationErasureReceiptDispatcher(
      DSLContext dsl,
      KafkaTemplate<String, byte[]> kafka,
      TransactionTemplate transactions,
      Clock clock,
      @Value("${conversation.erasure-receipt-topic}") String topic,
      MeterRegistry meters) {
    return new ConversationErasureReceiptDispatcher(dsl, kafka, transactions, clock, topic, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  JdbcTenantLifecycleRepository tenantLifecycleRepository(DataSource dataSource) {
    return new JdbcTenantLifecycleRepository(dataSource);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "event-runtime-enabled",
      havingValue = "true")
  TenantLifecycleListener tenantLifecycleListener(
      JdbcTenantLifecycleRepository repository, Clock clock) {
    return new TenantLifecycleListener(repository, clock);
  }

  @Bean
  ConversationAuthority conversationAuthority(
      IdentityJwtVerifier verifier, GrpcPermissionAuthorizer authorizer) {
    return new ConversationAuthority(verifier, authorizer);
  }

  @Bean
  JdbcConversationRepository conversationRepository(
      DataSource dataSource, AesGcmContentCrypto contentCrypto) {
    return new JdbcConversationRepository(dataSource, contentCrypto);
  }

  @Bean
  JdbcModelRunRepository modelRunRepository(
      DataSource dataSource, AesGcmContentCrypto contentCrypto) {
    return new JdbcModelRunRepository(dataSource, contentCrypto);
  }

  @Bean
  JdbcModelRunWorkerRepository modelRunWorkerRepository(
      DataSource dataSource, AesGcmContentCrypto contentCrypto) {
    return new JdbcModelRunWorkerRepository(dataSource, contentCrypto);
  }

  @Bean
  GitGovernedModelPolicyProvider modelPolicyProvider(ConversationProperties properties) {
    return new GitGovernedModelPolicyProvider(properties.providerRuntimeEnabled());
  }

  @Bean
  FileBackedOpenAiModelProvider modelProvider(ModelWorkerProperties properties) {
    return new FileBackedOpenAiModelProvider(properties.providerApiKeyPath());
  }

  @Bean
  ModelRunWorker modelRunWorker(
      GitGovernedModelPolicyProvider policies,
      JdbcModelRunWorkerRepository runs,
      FileBackedOpenAiModelProvider provider,
      Clock clock,
      ModelWorkerProperties properties) {
    return new ModelRunWorker(
        policies,
        runs,
        provider,
        clock,
        properties.leaseDuration(),
        properties.maximumConcurrentPerTenant(),
        properties.expiryBatchSize(),
        properties.circuitFailureThreshold(),
        properties.circuitOpenDuration());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "conversation",
      name = "provider-runtime-enabled",
      havingValue = "true")
  ModelRunWorkerLifecycle modelRunWorkerLifecycle(
      ModelRunWorker worker, ModelWorkerProperties properties) {
    return new ModelRunWorkerLifecycle(
        worker, properties.pollInterval(), properties.maximumConcurrentCalls());
  }

  @Bean
  ConversationService conversationService(
      ConversationAuthority authority,
      JdbcConversationRepository repository,
      JdbcModelRunRepository modelRuns,
      GitGovernedModelPolicyProvider modelPolicy,
      Clock clock) {
    return new ConversationService(authority, repository, modelRuns, modelPolicy, clock);
  }

  @Bean
  ConversationGrpcService conversationGrpcService(ConversationService service) {
    return new ConversationGrpcService(service);
  }

  @Bean
  BearerTokenServerInterceptor bearerTokenServerInterceptor() {
    return new BearerTokenServerInterceptor();
  }

  @Bean
  ContractValidationServerInterceptor contractValidation(MeterRegistry meters) {
    var rejections = meters.counter("hooshix.contract.validation.rejections");
    return new ContractValidationServerInterceptor(ignored -> rejections.increment());
  }

  @Bean
  ConversationTracingInterceptor conversationTracing(
      OpenTelemetry telemetry, MeterRegistry meters) {
    return new ConversationTracingInterceptor(telemetry, meters);
  }

  @Bean
  GrpcServerLifecycle grpcServerLifecycle(
      ConversationGrpcService service,
      ConversationTracingInterceptor tracing,
      BearerTokenServerInterceptor bearerToken,
      ContractValidationServerInterceptor validation,
      @Value("${hooshix.conversation.grpc-bind-address:0.0.0.0}") String bindAddress,
      @Value("${hooshix.conversation.grpc-port:9090}") int port,
      @Value("${hooshix.conversation.grpc-maximum-concurrent-calls:32}") int concurrency) {
    return new GrpcServerLifecycle(
        bindAddress, port, concurrency, service, List.of(tracing, bearerToken, validation));
  }

  @Bean("conversationReadiness")
  ConversationReadinessHealthIndicator conversationReadiness(
      DataSource dataSource,
      FileBackedContentKeyRing keyRing,
      IdentityJwtVerifier jwtVerifier,
      ConversationProperties properties,
      GitGovernedModelPolicyProvider modelPolicy,
      FileBackedOpenAiModelProvider modelProvider) {
    return new ConversationReadinessHealthIndicator(
        dataSource,
        keyRing,
        jwtVerifier,
        properties.providerRuntimeEnabled(),
        modelPolicy,
        modelProvider);
  }
}
