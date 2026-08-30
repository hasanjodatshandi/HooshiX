package com.sajtech.identity.configuration;

import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.authentication.usecase.*;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.erasure.usecase.ErasureUseCase;
import com.sajtech.identity.application.erasure.usecase.LegalHoldUseCase;
import com.sajtech.identity.application.erasure.usecase.ParticipantErasureUseCase;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.usecase.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.security.externalidentity.*;
import com.sajtech.identity.interfaces.erasure.grpc.IdentityErasureGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!migration")
class IdentityTenantRuntimeConfiguration {
  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ErasureUseCase erasureCoordination(
      ErasureStore erasureStore,
      AuthenticationStore authenticationStore,
      SessionCredentialPort sessionCredentials,
      MfaStore mfaStore,
      MfaCryptographyPort mfaCryptography,
      TransactionRunner transactions,
      Clock clock) {
    return new ErasureUseCase(
        erasureStore,
        authenticationStore,
        sessionCredentials,
        mfaStore,
        mfaCryptography,
        transactions,
        clock);
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ObservedErasure observedErasure(
      ErasureUseCase delegate, ObservationRegistry observations, MeterRegistry meters) {
    return new ObservedErasure(delegate, observations, meters);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = "authentication-runtime-enabled",
      havingValue = "true")
  ParticipantErasureUseCase participantErasureCoordination(
      ErasureStore erasureStore, TransactionRunner transactions, Clock clock) {
    return new ParticipantErasureUseCase(erasureStore, transactions, clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = {"authentication-runtime-enabled", "tenant-runtime-enabled"},
      havingValue = "true")
  LegalHoldUseCase legalHoldManagement(
      ErasureStore erasureStore,
      AuthenticationStore authenticationStore,
      SessionCredentialPort sessionCredentials,
      MfaStore mfaStore,
      MfaCryptographyPort mfaCryptography,
      com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort authorization,
      TransactionRunner transactions,
      Clock clock) {
    return new LegalHoldUseCase(
        erasureStore,
        authenticationStore,
        sessionCredentials,
        mfaStore,
        mfaCryptography,
        authorization,
        transactions,
        clock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "identity",
      name = {"authentication-runtime-enabled", "tenant-runtime-enabled"},
      havingValue = "true")
  IdentityErasureGrpcService erasureGrpc(
      ObservedErasure erasure,
      ParticipantErasureUseCase participants,
      LegalHoldUseCase legalHolds) {
    return new IdentityErasureGrpcService(erasure, participants, legalHolds);
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
  @ConditionalOnProperty(prefix = "identity", name = "tenant-runtime-enabled", havingValue = "true")
  com.sajtech.identity.infrastructure.worker.InvitationExpiryWorker invitationExpiryWorker(
      com.sajtech.identity.infrastructure.persistence.JooqTenantStore store,
      TransactionRunner tx,
      Clock clock,
      MeterRegistry meters) {
    return new com.sajtech.identity.infrastructure.worker.InvitationExpiryWorker(
        store, tx, clock, meters);
  }
}
