package com.sajtech.notification.infrastructure.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class FileBackedNotificationProviderConfiguration {
  private FileBackedNotificationProviderConfiguration() {}

  public static NotificationProviderConfiguration load(Path path) {
    if (path == null || !Files.isRegularFile(path)) {
      throw new IllegalStateException("Notification provider configuration file is unavailable");
    }
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Unable to read Notification provider configuration", exception);
    }
    try {
      return new NotificationProviderConfiguration(
          required(properties, "liara.smtp.host"),
          Integer.parseInt(required(properties, "liara.smtp.port")),
          required(properties, "liara.smtp.username"),
          required(properties, "liara.smtp.password"),
          URI.create(required(properties, "ippanel.base-uri")),
          required(properties, "ippanel.api-key"),
          required(properties, "ippanel.from-number"));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Notification provider configuration is invalid", exception);
    }
  }

  private static String required(Properties properties, String key) {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Required provider configuration key is missing: " + key);
    }
    return value.trim();
  }
}
