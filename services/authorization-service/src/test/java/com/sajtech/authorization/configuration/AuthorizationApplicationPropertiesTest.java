package com.sajtech.authorization.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class AuthorizationApplicationPropertiesTest {
  @Test
  void applicationYamlDisablesDuplicateOtlpMetricsExport() throws Exception {
    var loader = new YamlPropertySourceLoader();
    var loaded = loader.load("application", new ClassPathResource("application.yaml"));

    assertThat(loaded)
        .extracting(source -> source.getProperty("management.otlp.metrics.export.enabled"))
        .contains(false);
  }
}
