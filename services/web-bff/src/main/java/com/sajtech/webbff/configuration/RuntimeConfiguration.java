package com.sajtech.webbff.configuration;

import com.sajtech.webbff.infrastructure.client.*;
import com.sajtech.webbff.infrastructure.health.WebBffReadinessHealthIndicator;
import com.sajtech.webbff.infrastructure.observability.*;
import com.sajtech.webbff.infrastructure.security.*;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import com.sajtech.webbff.infrastructure.session.RedisBffSessionRepository;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
@EnableConfigurationProperties(WebBffProperties.class)
public class RuntimeConfiguration {
  @Bean
  Clock webBffClock() {
    return Clock.systemUTC();
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
      @Qualifier("webBffRefreshKeys") FileBackedKeyRing c) {
    return new SecurityMaterialRefresher(List.of(a, b, c));
  }

  @Bean(destroyMethod = "close")
  RedisBffSessionRepository browserSessions(
      WebBffProperties p,
      SessionCrypto crypto,
      Clock c,
      io.micrometer.core.instrument.MeterRegistry meters) {
    return new RedisBffSessionRepository(p.redisUri(), crypto, c, meters);
  }

  @Bean
  WebBffReadinessHealthIndicator webBffReadiness(
      WebBffProperties p,
      @Qualifier("webBffLocatorKeys") FileBackedKeyRing a,
      @Qualifier("webBffCsrfKeys") FileBackedKeyRing b,
      @Qualifier("webBffRefreshKeys") FileBackedKeyRing c,
      RedisBffSessionRepository sessions) {
    return new WebBffReadinessHealthIndicator(p, List.of(a, b, c), sessions);
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
