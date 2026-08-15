package com.sajtech.compromisedpassword.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.sajtech.compromisedpassword.configuration.CompromisedPasswordApplication;
import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag("integration")
@SpringBootTest(classes = CompromisedPasswordApplication.class)
class CompromisedPasswordRuntimeIntegrationTest {
  private static final String HASH = "ABCDE" + "9".repeat(35);
  private static final Path DATASET = createDataset();
  private static final String DATASET_SHA256 = sha256(DATASET);
  private static final int GRPC_PORT = availablePort();

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("hooshix.compromised-password.grpc-port", () -> GRPC_PORT);
    registry.add("hooshix.compromised-password.max-concurrent-lookups", () -> 4);
    registry.add("hooshix.compromised-password.dataset.path", DATASET::toString);
    registry.add("hooshix.compromised-password.dataset.expected-sha256", () -> DATASET_SHA256);
    registry.add("hooshix.compromised-password.dataset.acquired-at", Instant.now()::toString);
    registry.add("hooshix.compromised-password.dataset.format-version", () -> 1);
    registry.add("hooshix.compromised-password.dataset.max-prefix-cardinality", () -> 16);
    registry.add("management.server.port", () -> 0);
    registry.add(
        "management.opentelemetry.tracing.export.otlp.endpoint",
        () -> "http://127.0.0.1:1/v1/traces");
    registry.add("management.opentelemetry.tracing.export.otlp.connect-timeout", () -> "100ms");
    registry.add("management.opentelemetry.tracing.export.otlp.timeout", () -> "100ms");
  }

  @Test
  void lookupSucceedsWhenTelemetryEndpointIsUnavailable() throws Exception {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", GRPC_PORT).usePlaintext().build();
    try {
      var response =
          CompromisedPasswordServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(900, TimeUnit.MILLISECONDS)
              .lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix("ABCDE").build());

      assertThat(response.getMatchesList()).hasSize(1);
      assertThat(response.getMatches(0).getSuffix()).isEqualTo("9".repeat(35));
      assertThat(response.getMatches(0).getOccurrenceCount()).isEqualTo(11L);
    } finally {
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Path createDataset() {
    try {
      Path path = Files.createTempFile("compromised-password-runtime-", ".sqlite");
      path.toFile().deleteOnExit();
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          Statement statement = connection.createStatement()) {
        statement.execute(
            "CREATE TABLE compromised_password ("
                + "prefix INTEGER NOT NULL CHECK (prefix BETWEEN 0 AND 1048575),"
                + "hash BLOB NOT NULL CHECK (length(hash) = 20),"
                + "occurrence_count INTEGER NOT NULL CHECK (occurrence_count > 0),"
                + "PRIMARY KEY (prefix, hash)) WITHOUT ROWID");
      }
      try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
          PreparedStatement statement =
              connection.prepareStatement(
                  "INSERT INTO compromised_password(prefix, hash, occurrence_count) VALUES (?, ?, ?)")) {
        statement.setInt(1, Integer.parseInt(HASH.substring(0, 5), 16));
        statement.setBytes(2, HexFormat.of().parseHex(HASH));
        statement.setLong(3, 11L);
        statement.executeUpdate();
      }
      return path;
    } catch (IOException | java.sql.SQLException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String sha256(Path path) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException | NoSuchAlgorithmException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static int availablePort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }
}
