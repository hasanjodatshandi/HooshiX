package com.sajtech.authorization.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;

class KafkaClientConfigTreeTest {
  @Test
  void independentConfigTreeBindsPemAndHostnameValidationForEveryClient(@TempDir Path directory)
      throws Exception {
    String chain = "synthetic-leaf\nsynthetic-intermediate";
    String key = "synthetic-private-material\nsecond-line";
    String root = "synthetic-public-root";
    Map<String, String> fields =
        Map.of(
            "spring.kafka.security.protocol", "SSL",
            "spring.kafka.ssl.key-store-type", "PEM",
            "spring.kafka.ssl.key-store-certificate-chain", chain,
            "spring.kafka.ssl.key-store-key", key,
            "spring.kafka.ssl.trust-store-type", "PEM",
            "spring.kafka.ssl.trust-store-certificates", root,
            "spring.kafka.properties.ssl.endpoint.identification.algorithm", "HTTPS");
    for (var field : fields.entrySet()) {
      Files.writeString(directory.resolve(field.getKey()), field.getValue());
    }
    var source = new ConfigTreePropertySource("kafka-client", directory);
    var properties =
        new Binder(ConfigurationPropertySources.from(source))
            .bind("spring.kafka", Bindable.of(KafkaProperties.class))
            .get();

    for (var client :
        List.of(
            properties.buildConsumerProperties(),
            properties.buildProducerProperties(),
            properties.buildAdminProperties())) {
      assertThat(client)
          .containsEntry("security.protocol", "SSL")
          .containsEntry("ssl.keystore.type", "PEM")
          .containsEntry("ssl.keystore.certificate.chain", chain)
          .containsEntry("ssl.keystore.key", key)
          .containsEntry("ssl.truststore.type", "PEM")
          .containsEntry("ssl.truststore.certificates", root)
          .containsEntry("ssl.endpoint.identification.algorithm", "HTTPS");
    }
  }
}
