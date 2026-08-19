package com.sajtech.identity.infrastructure.quota;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileHostTimeHealth implements HostTimeHealth {
  private final Path path;

  public FileHostTimeHealth(Path path) {
    this.path = path;
  }

  @Override
  public boolean synchronizedHealthy() {
    try {
      return Files.isRegularFile(path)
          && "synchronized".equals(Files.readString(path, StandardCharsets.UTF_8).trim());
    } catch (IOException exception) {
      return false;
    }
  }
}
