package com.sajtech.webbff.infrastructure.session;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.infrastructure.security.SessionCrypto;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class RedisOidcPreauthRepositoryIntegrationTest {
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
          "redis:8.2.8-bookworm@sha256:2f7462b9e93e0a7ae2edf3a0a0babc8a4d29f8bfc50849b906b7caaef925edc1");
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(IMAGE)
          .withExposedPorts(6379)
          .withCommand(
              "redis-server",
              "--maxmemory",
              "64mb",
              "--maxmemory-policy",
              "noeviction",
              "--save",
              "");
  @TempDir Path temp;
  private RedisOidcPreauthRepository repository;
  private Clock clock;

  @BeforeAll
  static void startRedis() {
    REDIS.start();
  }

  @AfterAll
  static void stopRedis() {
    REDIS.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
    SessionCrypto crypto =
        new SessionCrypto(
            ring("locator", (byte) 1, "HmacSHA256", Duration.ofMinutes(5)),
            ring("csrf", (byte) 2, "HmacSHA256", Duration.ofMinutes(5)),
            ring("refresh", (byte) 3, "AES", Duration.ofHours(1)));
    repository = new RedisOidcPreauthRepository(redisUri(), crypto, clock);
    repository.connection().sync().flushall();
  }

  @AfterEach
  void close() {
    repository.close();
  }

  @Test
  void stateIsSingleUseAndRawBrowserSecretsNeverBecomeRedisKeysOrValues() {
    OidcAuthorizationStart start =
        repository.begin(
            null,
            OidcPurpose.LOGIN,
            "browser-locator",
            "https://app.example.test/api/v1/auth/oidc/google/callback",
            "/welcome");

    assertThat(start.state()).hasSize(43);
    assertThat(start.nonce()).hasSize(43);
    assertThat(start.verifier()).hasSize(43);
    assertThat(start.codeChallenge()).hasSize(43).isNotEqualTo(start.verifier());
    Map<String, String> raw =
        repository.connection().sync().hgetall(repository.connection().sync().keys("*").getFirst());
    assertThat(repository.connection().sync().keys("*"))
        .noneMatch(key -> key.contains(start.preauthCookie()) || key.contains(start.state()));
    assertThat(raw.values())
        .noneMatch(
            value ->
                value.contains(start.nonce())
                    || value.contains(start.verifier())
                    || value.contains("/welcome"));

    OidcPreauthTransaction consumed =
        repository.consume(start.preauthCookie(), start.state()).orElseThrow();
    assertThat(consumed.nonce()).isEqualTo(start.nonce());
    assertThat(consumed.verifier()).isEqualTo(start.verifier());
    assertThat(repository.consume(start.preauthCookie(), start.state())).isEmpty();
  }

  @Test
  void browserContainerHasAtMostFiveLiveTransactions() {
    String cookie = null;
    for (int index = 0; index < 5; index++) {
      OidcAuthorizationStart started =
          repository.begin(
              cookie,
              OidcPurpose.LOGIN,
              "browser-locator",
              "https://app.example.test/api/v1/auth/oidc/google/callback",
              "/welcome");
      cookie = started.preauthCookie();
    }
    String existing = cookie;

    assertThatThrownBy(
            () ->
                repository.begin(
                    existing,
                    OidcPurpose.LOGIN,
                    "browser-locator",
                    "https://app.example.test/api/v1/auth/oidc/google/callback",
                    "/welcome"))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.RATE_LIMITED));
  }

  private FileBackedKeyRing ring(String name, byte fill, String algorithm, Duration stale)
      throws Exception {
    Path path = temp.resolve(name + ".properties");
    byte[] key = new byte[32];
    Arrays.fill(key, fill);
    Files.writeString(
        path, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");
    return new FileBackedKeyRing(path, algorithm, 32, clock, stale);
  }

  private static String redisUri() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }
}
