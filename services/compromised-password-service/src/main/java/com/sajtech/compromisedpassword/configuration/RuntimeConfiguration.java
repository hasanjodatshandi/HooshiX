package com.sajtech.compromisedpassword.configuration;

import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.application.lookup.port.out.CompromisedPasswordRepository;
import com.sajtech.compromisedpassword.application.lookup.usecase.LookupCompromisedPasswordsUseCase;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetGuard;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetSettings;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.DatasetState;
import com.sajtech.compromisedpassword.infrastructure.lookup.dataset.SqliteCompromisedPasswordRepository;
import com.sajtech.compromisedpassword.infrastructure.lookup.runtime.BoundedLookupCompromisedPasswords;
import com.sajtech.compromisedpassword.infrastructure.observability.health.DatasetHealthIndicator;
import com.sajtech.compromisedpassword.infrastructure.runtime.grpc.GrpcServerLifecycle;
import com.sajtech.compromisedpassword.interfaces.lookup.grpc.CompromisedPasswordGrpcService;
import com.sajtech.compromisedpassword.interfaces.observability.grpc.SafeTracingServerInterceptor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CompromisedPasswordProperties.class)
public class RuntimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  DatasetSettings datasetSettings(CompromisedPasswordProperties properties) {
    CompromisedPasswordProperties.Dataset dataset = properties.dataset();
    return new DatasetSettings(
        dataset.path(),
        dataset.manifestPath(),
        dataset.requiredSourceKind(),
        dataset.maxPrefixCardinality(),
        dataset.maxSerializedResponseBytes());
  }

  @Bean
  DatasetGuard datasetGuard(DatasetSettings settings, Clock clock) {
    return new DatasetGuard(settings, clock);
  }

  @Bean
  Gauge compromisedPasswordDatasetReadyGauge(
      DatasetGuard datasetGuard, MeterRegistry meterRegistry) {
    return Gauge.builder(
            "compromised_password.dataset.ready",
            datasetGuard,
            guard -> guard.state() == DatasetState.READY ? 1.0 : 0.0)
        .description("Whether the approved compromised-password dataset is ready")
        .register(meterRegistry);
  }

  @Bean
  CompromisedPasswordRepository compromisedPasswordRepository(
      DatasetGuard datasetGuard, DatasetSettings settings) {
    return new SqliteCompromisedPasswordRepository(datasetGuard, settings.maxPrefixCardinality());
  }

  @Bean
  LookupCompromisedPasswords lookupCompromisedPasswordsUseCase(
      CompromisedPasswordRepository repository,
      CompromisedPasswordProperties properties,
      ObservationRegistry observationRegistry,
      MeterRegistry meterRegistry) {
    LookupCompromisedPasswords core = new LookupCompromisedPasswordsUseCase(repository);
    return new BoundedLookupCompromisedPasswords(
        core, properties.maxConcurrentLookups(), observationRegistry, meterRegistry);
  }

  @Bean
  CompromisedPasswordGrpcService compromisedPasswordGrpcService(
      LookupCompromisedPasswords lookup, DatasetSettings settings) {
    return new CompromisedPasswordGrpcService(lookup, settings.maxSerializedResponseBytes());
  }

  @Bean
  SafeTracingServerInterceptor safeTracingServerInterceptor(OpenTelemetry openTelemetry) {
    return new SafeTracingServerInterceptor(openTelemetry);
  }

  @Bean
  GrpcServerLifecycle grpcServerLifecycle(
      CompromisedPasswordProperties properties,
      CompromisedPasswordGrpcService service,
      SafeTracingServerInterceptor interceptor) {
    return new GrpcServerLifecycle(properties.grpcPort(), service, interceptor);
  }

  @Bean(name = "compromisedPasswordDatasetHealthIndicator")
  DatasetHealthIndicator compromisedPasswordDatasetHealthIndicator(DatasetGuard datasetGuard) {
    return new DatasetHealthIndicator(datasetGuard);
  }
}
