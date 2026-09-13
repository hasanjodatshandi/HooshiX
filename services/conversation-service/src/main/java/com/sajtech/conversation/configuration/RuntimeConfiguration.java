package com.sajtech.conversation.configuration;

import com.sajtech.conversation.infrastructure.health.ConversationReadinessHealthIndicator;
import com.sajtech.conversation.infrastructure.security.content.AesGcmContentCrypto;
import com.sajtech.conversation.infrastructure.security.keyring.ContentKeyRingRefresher;
import com.sajtech.conversation.infrastructure.security.keyring.FileBackedContentKeyRing;
import java.security.SecureRandom;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!migration")
@EnableConfigurationProperties(ConversationProperties.class)
public class RuntimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  FileBackedContentKeyRing contentKeyRing(ConversationProperties properties, Clock clock) {
    return new FileBackedContentKeyRing(
        properties.contentKeyRingPath(), clock, properties.keyRingMaximumStaleness());
  }

  @Bean
  AesGcmContentCrypto contentCrypto(FileBackedContentKeyRing keyRing) {
    return new AesGcmContentCrypto(keyRing, new SecureRandom());
  }

  @Bean
  ContentKeyRingRefresher contentKeyRingRefresher(FileBackedContentKeyRing keyRing) {
    return new ContentKeyRingRefresher(keyRing);
  }

  @Bean("conversationReadiness")
  ConversationReadinessHealthIndicator conversationReadiness(
      DataSource dataSource, FileBackedContentKeyRing keyRing) {
    return new ConversationReadinessHealthIndicator(dataSource, keyRing);
  }
}
