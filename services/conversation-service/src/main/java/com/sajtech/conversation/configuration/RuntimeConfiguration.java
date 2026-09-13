package com.sajtech.conversation.configuration;

import com.sajtech.conversation.application.service.ConversationAuthority;
import com.sajtech.conversation.application.service.ConversationService;
import com.sajtech.conversation.infrastructure.client.authorization.GrpcPermissionAuthorizer;
import com.sajtech.conversation.infrastructure.health.ConversationReadinessHealthIndicator;
import com.sajtech.conversation.infrastructure.persistence.JdbcConversationRepository;
import com.sajtech.conversation.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifierRefresher;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.keyring.ContentKeyRingRefresher;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import com.sajtech.conversation.interfaces.grpc.BearerTokenServerInterceptor;
import com.sajtech.conversation.interfaces.grpc.ConversationGrpcService;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!migration")
@EnableConfigurationProperties(ConversationProperties.class)
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

  @Bean(destroyMethod = "shutdownNow")
  ManagedChannel conversationAuthorizationChannel(ConversationProperties properties) {
    return NettyChannelBuilder.forTarget(properties.authorizationTarget())
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(16 * 1024)
        .build();
  }

  @Bean
  GrpcPermissionAuthorizer permissionAuthorizer(
      ManagedChannel conversationAuthorizationChannel, ConversationProperties properties) {
    return new GrpcPermissionAuthorizer(
        conversationAuthorizationChannel, properties.authorizationMaximumConcurrentChecks());
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
  ConversationService conversationService(
      ConversationAuthority authority, JdbcConversationRepository repository, Clock clock) {
    return new ConversationService(authority, repository, clock);
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
      DataSource dataSource, FileBackedContentKeyRing keyRing, IdentityJwtVerifier jwtVerifier) {
    return new ConversationReadinessHealthIndicator(dataSource, keyRing, jwtVerifier);
  }
}
