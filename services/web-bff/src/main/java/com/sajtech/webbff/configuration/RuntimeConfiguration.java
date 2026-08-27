package com.sajtech.webbff.configuration;

import com.sajtech.webbff.infrastructure.client.*;
import com.sajtech.webbff.infrastructure.erasure.*;
import com.sajtech.webbff.infrastructure.health.WebBffReadinessHealthIndicator;
import com.sajtech.webbff.infrastructure.observability.*;
import com.sajtech.webbff.infrastructure.quota.*;
import com.sajtech.webbff.infrastructure.security.*;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import com.sajtech.webbff.infrastructure.session.RedisOidcPreauthRepository;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("!migration")
@EnableConfigurationProperties(WebBffProperties.class)
public class RuntimeConfiguration {
  @Bean
  Clock webBffClock() {
    return Clock.systemUTC();
  }

  @Bean
  TransactionTemplate webBffTransactions(PlatformTransactionManager manager) {
    return new TransactionTemplate(manager);
  }

  @Bean
  @ConditionalOnProperty(prefix = "web-bff", name = "erasure-runtime-enabled", havingValue = "true")
  JooqWebBffErasureRepository webBffErasureRepository(DSLContext dsl) {
    return new JooqWebBffErasureRepository(dsl);
  }

  @Bean
  @ConditionalOnProperty(prefix = "web-bff", name = "erasure-runtime-enabled", havingValue = "true")
  IdentityErasureTargetClient identityErasureTargetClient(
      @Qualifier("webBffIdentityChannel") ManagedChannel channel) {
    return new IdentityErasureTargetClient(channel);
  }

  @Bean
  @ConditionalOnProperty(prefix = "web-bff", name = "erasure-runtime-enabled", havingValue = "true")
  WebBffErasureListener webBffErasureListener(
      JooqWebBffErasureRepository repository, TransactionTemplate transactions, Clock clock) {
    return new WebBffErasureListener(repository, transactions, clock);
  }

  @Bean
  @ConditionalOnProperty(prefix = "web-bff", name = "erasure-runtime-enabled", havingValue = "true")
  WebBffErasureWorker webBffErasureWorker(
      IdentityErasureTargetClient identity,
      JooqWebBffErasureRepository repository,
      RedisBffSessionRepository sessions,
      TransactionTemplate transactions,
      Clock clock,
      MeterRegistry meters) {
    return new WebBffErasureWorker(identity, repository, sessions, transactions, clock, meters);
  }

  @Bean
  @ConditionalOnProperty(prefix = "web-bff", name = "erasure-runtime-enabled", havingValue = "true")
  WebBffErasureReceiptDispatcher webBffErasureReceiptDispatcher(
      DSLContext dsl,
      org.springframework.kafka.core.KafkaTemplate<String, byte[]> kafka,
      TransactionTemplate transactions,
      Clock clock,
      @Value("${web-bff.erasure-receipt-topic:hooshix.identity.erasure.receipt.v1}") String topic,
      MeterRegistry meters) {
    return new WebBffErasureReceiptDispatcher(dsl, kafka, transactions, clock, topic, meters);
  }

  @Bean("webBffLocatorKeys")
  FileBackedKeyRing locator(WebBffProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.locatorKeyRingPath(), "HmacSHA256", 32, c, p.hmacKeyMaximumStaleness());
  }

  @Bean("webBffCsrfKeys")
  FileBackedKeyRing csrf(WebBffProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.csrfKeyRingPath(), "HmacSHA256", 32, c, p.hmacKeyMaximumStaleness());
  }

  @Bean("webBffRefreshKeys")
  FileBackedKeyRing refresh(WebBffProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.refreshEncryptionKeyRingPath(), "AES", 32, c, p.encryptionKeyMaximumStaleness());
  }

  @Bean("webBffQuotaKeys")
  FileBackedKeyRing quotaKeys(WebBffProperties p, Clock c) {
    return new FileBackedKeyRing(
        p.quotaKeyRingPath(), "HmacSHA256", 32, c, p.hmacKeyMaximumStaleness());
  }

  @Bean
  SessionCrypto sessionCrypto(
      @Qualifier("webBffLocatorKeys") FileBackedKeyRing a,
      @Qualifier("webBffCsrfKeys") FileBackedKeyRing b,
      @Qualifier("webBffRefreshKeys") FileBackedKeyRing c) {
    return new SessionCrypto(a, b, c);
  }

  @Bean
  SecurityMaterialRefresher webBffSecurityMaterialRefresher(
      @Qualifier("webBffLocatorKeys") FileBackedKeyRing a,
      @Qualifier("webBffCsrfKeys") FileBackedKeyRing b,
      @Qualifier("webBffRefreshKeys") FileBackedKeyRing c,
      @Qualifier("webBffQuotaKeys") FileBackedKeyRing d) {
    return new SecurityMaterialRefresher(List.of(a, b, c, d));
  }

  @Bean(destroyMethod = "close")
  RedisBffSessionRepository browserSessions(
      WebBffProperties p,
      SessionCrypto crypto,
      Clock c,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new RedisBffSessionRepository(p.redisUri(), crypto, c, meters);
  }

  @Bean(destroyMethod = "close")
  RedisOidcPreauthRepository oidcPreauth(
      WebBffProperties properties, SessionCrypto crypto, Clock clock) {
    return new RedisOidcPreauthRepository(properties.redisUri(), crypto, clock);
  }

  @Bean
  GoogleOidcClient googleOidcProvider(
      WebBffProperties properties,
      ObservationRegistry observations,
      MeterRegistry meters,
      Clock clock) {
    return new GoogleOidcClient(properties, observations, meters, clock);
  }

  @Bean
  OidcClockSafetyGuard oidcClockSafetyGuard(Clock clock) {
    return new OidcClockSafetyGuard(clock);
  }

  @Bean
  OidcHostTimeHealth oidcHostTimeHealth(WebBffProperties properties) {
    return new OidcHostTimeHealth(properties.oidcQuota().hostTimeStatusPath());
  }

  @Bean
  OidcQuotaKeyEncoder oidcQuotaKeyEncoder(@Qualifier("webBffQuotaKeys") FileBackedKeyRing keys) {
    return new OidcQuotaKeyEncoder(keys);
  }

  @Bean(destroyMethod = "close")
  RedisOidcQuota oidcQuota(
      WebBffProperties properties,
      OidcQuotaKeyEncoder keys,
      OidcClockSafetyGuard guard,
      OidcHostTimeHealth hostTime,
      MeterRegistry meters) {
    return new RedisOidcQuota(
        properties.redisUri(), keys, guard, hostTime, properties.oidcQuota(), meters);
  }

  @Bean
  RedisOidcQuotaCleanupWorker oidcQuotaCleanup(
      RedisOidcQuota quota, OidcClockSafetyGuard guard, OidcHostTimeHealth hostTime, Clock clock) {
    return new RedisOidcQuotaCleanupWorker(quota, guard, hostTime, clock);
  }

  @Bean
  WebBffReadinessHealthIndicator webBffReadiness(
      WebBffProperties p,
      @Qualifier("webBffLocatorKeys") FileBackedKeyRing a,
      @Qualifier("webBffCsrfKeys") FileBackedKeyRing b,
      @Qualifier("webBffRefreshKeys") FileBackedKeyRing c,
      @Qualifier("webBffQuotaKeys") FileBackedKeyRing d,
      RedisBffSessionRepository sessions,
      RedisOidcQuota quota,
      OidcHostTimeHealth hostTime) {
    return new WebBffReadinessHealthIndicator(p, List.of(a, b, c, d), sessions, quota, hostTime);
  }

  @Bean(destroyMethod = "shutdownNow", name = "webBffIdentityChannel")
  ManagedChannel identityChannel(
      WebBffProperties p, OpenTelemetry telemetry, MeterRegistry meters) {
    return channel(
        p.identityTarget(), "identity", p.identityMaximumConcurrentCalls(), telemetry, meters);
  }

  @Bean(destroyMethod = "shutdownNow", name = "webBffAuthorizationChannel")
  ManagedChannel authorizationChannel(
      WebBffProperties p, OpenTelemetry telemetry, MeterRegistry meters) {
    return channel(
        p.authorizationTarget(),
        "authorization",
        p.authorizationMaximumConcurrentCalls(),
        telemetry,
        meters);
  }

  @Bean
  IdentityBffClient identityBffClient(@Qualifier("webBffIdentityChannel") ManagedChannel c) {
    return new IdentityBffClient(c);
  }

  @Bean
  AuthorizationBffClient authorizationBffClient(
      @Qualifier("webBffAuthorizationChannel") ManagedChannel c) {
    return new AuthorizationBffClient(c);
  }

  @Bean
  TrustedClientAddress trustedClientAddress() {
    return new TrustedClientAddress();
  }

  @Bean
  FilterRegistrationBean<BrowserSecurityFilter> browserSecurityFilter(
      WebBffProperties p, RedisBffSessionRepository s) {
    var bean = new FilterRegistrationBean<>(new BrowserSecurityFilter(p, s));
    bean.setOrder(Integer.MIN_VALUE + 100);
    bean.addUrlPatterns("/*");
    return bean;
  }

  private static ManagedChannel channel(
      String target,
      String dependency,
      int maximumConcurrentCalls,
      OpenTelemetry telemetry,
      MeterRegistry meters) {
    return NettyChannelBuilder.forTarget(target)
        .usePlaintext()
        .disableRetry()
        .maxInboundMessageSize(64 * 1024)
        .intercept(
            new BffDependencyAdmissionInterceptor(dependency, maximumConcurrentCalls, meters),
            new BffDependencyObservabilityInterceptor(dependency, telemetry, meters))
        .build();
  }
}
