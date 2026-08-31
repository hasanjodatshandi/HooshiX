package com.sajtech.identity.configuration;

import com.sajtech.hooshix.contract.validation.ContractValidationServerInterceptor;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.usecase.*;
import com.sajtech.identity.application.externalidentity.port.out.*;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.notification.port.in.ReportNotificationResult;
import com.sajtech.identity.application.notification.port.out.*;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.usecase.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.*;
import com.sajtech.identity.application.registration.usecase.*;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.infrastructure.client.notification.GrpcNotificationSubmissionClient;
import com.sajtech.identity.infrastructure.observability.*;
import com.sajtech.identity.infrastructure.persistence.*;
import com.sajtech.identity.infrastructure.quota.*;
import com.sajtech.identity.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.identity.infrastructure.security.externalidentity.*;
import com.sajtech.identity.infrastructure.security.jwt.FileBackedRsaSigningKeyRing;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.identity.infrastructure.worker.IdentityRetentionWorker;
import com.sajtech.identity.infrastructure.worker.NotificationOutboxDispatcher;
import com.sajtech.identity.interfaces.erasure.grpc.ErasureWorkloadIdentityInterceptor;
import com.sajtech.identity.interfaces.notification.grpc.IdentityNotificationResultGrpcService;
import com.sajtech.identity.interfaces.observability.grpc.IdentityAdmissionInterceptor;
import com.sajtech.identity.interfaces.observability.grpc.SafeTracingServerInterceptor;
import com.sajtech.identity.interfaces.observability.grpc.TransactionFailureServerInterceptor;
import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!migration")
class IdentityTransportRuntimeConfiguration {
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
  TransactionFailureServerInterceptor transactionFailureInterceptor(MeterRegistry meters) {
    return new TransactionFailureServerInterceptor(meters);
  }

  @Bean
  ErasureWorkloadIdentityInterceptor erasureWorkloadIdentityInterceptor() {
    return new ErasureWorkloadIdentityInterceptor();
  }

  @Bean
  ContractValidationServerInterceptor contractValidation(MeterRegistry meters) {
    var rejections = meters.counter("hooshix.contract.validation.rejections");
    return new ContractValidationServerInterceptor(ignored -> rejections.increment());
  }

  @Bean
  GrpcServerLifecycle grpcLifecycle(
      IdentityProperties p,
      List<BindableService> services,
      SafeTracingServerInterceptor tracing,
      ContractValidationServerInterceptor validation,
      IdentityAdmissionInterceptor admission,
      TransactionFailureServerInterceptor transactionFailures,
      ErasureWorkloadIdentityInterceptor erasureWorkload,
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
        validation,
        admission,
        List.of(transactionFailures, erasureWorkload));
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
      ContractValidationServerInterceptor validation,
      IdentityAdmissionInterceptor admission,
      TransactionFailureServerInterceptor transactionFailures,
      @Value("${identity.notification-result-grpc-bind-address:0.0.0.0}") String bindAddress) {
    return new GrpcServerLifecycle(
        bindAddress,
        p.notificationResultGrpcPort(),
        p.maxConcurrentCallsPerConnection(),
        true,
        List.of(new IdentityNotificationResultGrpcService(report)),
        tracing,
        validation,
        admission,
        List.of(transactionFailures));
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
      com.sajtech.identity.application.profile.port.out.ProfileContactStore profileContactStore,
      MfaStore mfaStore,
      ExternalIdentityStore externalIdentityStore,
      TransactionRunner transactions,
      Clock clock) {
    return new IdentityRetentionWorker(
        outboxStore,
        registrationStore,
        authenticationStore,
        profileContactStore,
        mfaStore,
        externalIdentityStore,
        transactions,
        clock);
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
      @Qualifier("quotaKeyRing") FileBackedKeyRing d,
      @Qualifier("mfaKeyRing") FileBackedKeyRing e) {
    return new IdentityReadinessHealthIndicator(dsl, quota, hostTime, a, b, c, d, e);
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
