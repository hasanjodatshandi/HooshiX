package com.sajtech.webbff.infrastructure.quota;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class OidcHostTimeHealth {
  private final Path path;

  public OidcHostTimeHealth(Path path) {
    this.path = path;
  }

  public boolean synchronizedHealthy() {
    try {
      return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
          && "synchronized".equals(Files.readString(path, StandardCharsets.UTF_8).strip());
    } catch (IOException exception) {
      return false;
    }
  }
}
