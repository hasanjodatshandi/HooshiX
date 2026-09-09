package com.sajtech.webbff.infrastructure.quota;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OidcHostTimeHealthTest {
  @TempDir Path temp;

  @Test
  void acceptsRegularAndKubernetesStyleProjectedFiles() throws Exception {
    Path regular = Files.writeString(temp.resolve("regular"), "synchronized\n");
    assertTrue(new OidcHostTimeHealth(regular).synchronizedHealthy());

    Path generation = Files.createDirectory(temp.resolve("..2026_09_09_00_00_00"));
    Files.writeString(generation.resolve("host-time-synchronized"), "synchronized\n");
    Files.createSymbolicLink(temp.resolve("..data"), generation.getFileName());
    Path projected =
        Files.createSymbolicLink(
            temp.resolve("host-time-synchronized"), Path.of("..data/host-time-synchronized"));
    assertTrue(new OidcHostTimeHealth(projected).synchronizedHealthy());
  }

  @Test
  void rejectsMissingUnsafeEscapedAndOversizedState() throws Exception {
    assertFalse(new OidcHostTimeHealth(temp.resolve("missing")).synchronizedHealthy());
    assertFalse(
        new OidcHostTimeHealth(Files.writeString(temp.resolve("unsafe"), "unsynchronized\n"))
            .synchronizedHealthy());
    assertFalse(
        new OidcHostTimeHealth(Files.writeString(temp.resolve("oversized"), "x".repeat(65)))
            .synchronizedHealthy());

    Path outside =
        Files.writeString(
            Files.createTempFile(
                Objects.requireNonNull(temp.getParent()), "outside-host-time-", ".txt"),
            "synchronized\n");
    try {
      Path escaped = Files.createSymbolicLink(temp.resolve("escaped"), outside);
      assertFalse(new OidcHostTimeHealth(escaped).synchronizedHealthy());
    } finally {
      Files.deleteIfExists(outside);
    }
  }
}
