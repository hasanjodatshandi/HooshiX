package com.sajtech.webbff.configuration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class StrictJsonConfigurationTest {
  @Test
  void applicationJsonMapperRejectsAmbiguousOrOutOfContractInput() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withInitializer(
            context -> {
              try {
                new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yaml"))
                    .forEach(context.getEnvironment().getPropertySources()::addLast);
              } catch (Exception exception) {
                throw new IllegalStateException(exception);
              }
            })
        .run(
            context -> {
              ObjectMapper mapper = context.getBean(ObjectMapper.class);
              assertThatThrownBy(() -> mapper.readValue("{\"count\":1,\"extra\":2}", Payload.class))
                  .isInstanceOf(RuntimeException.class);
              assertThatThrownBy(
                      () -> mapper.readValue("{\"count\":1}{\"count\":2}", Payload.class))
                  .isInstanceOf(RuntimeException.class);
              assertThatThrownBy(() -> mapper.readValue("{\"count\":1,\"count\":2}", Payload.class))
                  .isInstanceOf(RuntimeException.class);
              assertThatThrownBy(() -> mapper.readValue("{\"count\":\"1\"}", Payload.class))
                  .isInstanceOf(RuntimeException.class);
            });
  }

  private record Payload(int count) {}
}
