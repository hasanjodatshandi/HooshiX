package com.sajtech.webbff.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

class WebBffApplicationPropertiesTest {
  @Test
  void applicationYamlPreservesSlashBearingRouteAudienceKey() throws Exception {
    var loader = new YamlPropertySourceLoader();
    var loaded = loader.load("application", new ClassPathResource("application.yaml"));
    var propertySources = new MutablePropertySources();
    loaded.forEach(propertySources::addLast);

    var routeAudiences =
        new Binder(ConfigurationPropertySources.from(propertySources))
            .bind("web-bff.route-audiences", Bindable.mapOf(String.class, String.class))
            .orElseThrow(() -> new IllegalStateException("route audiences are unavailable"));

    assertThat(routeAudiences).containsEntry("/api/v1/authorization", "authorization-service");
  }
}
