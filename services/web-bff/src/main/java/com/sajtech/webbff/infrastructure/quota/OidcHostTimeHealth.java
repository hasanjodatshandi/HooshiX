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
      Path absolute = path.toAbsolutePath().normalize();
      Path parent = absolute.getParent();
      if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return false;
      Path trustedRoot = parent.toRealPath();
      Path resolved = absolute.toRealPath();
      return resolved.startsWith(trustedRoot)
          && Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
          && Files.size(resolved) <= 64
          && "synchronized".equals(Files.readString(resolved, StandardCharsets.UTF_8).strip());
    } catch (IOException exception) {
      return false;
    }
  }
}
