package com.sajtech.conversation.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaAutoConfigurationTest {
  @Test
  void providesConsumerAndProducerInfrastructure() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
        .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9094")
        .run(
            context -> {
              assertThat(context).hasSingleBean(ConsumerFactory.class);
              assertThat(context).hasSingleBean(KafkaTemplate.class);
            });
  }
}
