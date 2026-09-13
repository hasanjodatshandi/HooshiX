package com.sajtech.conversation.configuration;

import com.sajtech.conversation.application.service.ConversationAuthority;
import com.sajtech.conversation.infrastructure.client.authorization.GrpcPermissionAuthorizer;
import com.sajtech.conversation.infrastructure.health.ConversationReadinessHealthIndicator;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifier;
import com.sajtech.conversation.infrastructure.security.IdentityJwtVerifierRefresher;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.keyring.ContentKeyRingRefresher;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
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

  @Bean("conversationReadiness")
  ConversationReadinessHealthIndicator conversationReadiness(
      DataSource dataSource, FileBackedContentKeyRing keyRing, IdentityJwtVerifier jwtVerifier) {
    return new ConversationReadinessHealthIndicator(dataSource, keyRing, jwtVerifier);
  }
}
