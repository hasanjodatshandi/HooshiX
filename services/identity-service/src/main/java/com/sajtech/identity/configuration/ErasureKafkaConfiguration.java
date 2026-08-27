package com.sajtech.identity.configuration;

import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "identity", name = "erasure-runtime-enabled", havingValue = "true")
public class ErasureKafkaConfiguration {
  @Bean
  ConcurrentKafkaListenerContainerFactory<String, byte[]> erasureKafkaListenerContainerFactory(
      ConsumerFactory<String, byte[]> consumerFactory,
      KafkaTemplate<String, byte[]> kafkaTemplate) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
    var recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, exception) -> new TopicPartition(record.topic() + ".DLT", record.partition()));
    var errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(Duration.ofSeconds(1).toMillis(), 2));
    errorHandler.setCommitRecovered(true);
    factory.setCommonErrorHandler(errorHandler);
    return factory;
  }
}
