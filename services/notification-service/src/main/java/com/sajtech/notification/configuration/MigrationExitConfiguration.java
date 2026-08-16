package com.sajtech.notification.configuration;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("migration")
public class MigrationExitConfiguration {
  @Bean
  ApplicationRunner closeAfterMigration(ConfigurableApplicationContext context) {
    return arguments -> context.close();
  }
}
