package com.sajtech.webbff.infrastructure.session;

import static org.assertj.core.api.Assertions.*;

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
class RedisBffSessionRepositoryIntegrationTest {
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
  private RedisBffSessionRepository sessions;
  private Clock clock;

  @BeforeAll
  static void start() {
    REDIS.start();
  }

  @AfterAll
  static void stop() {
    REDIS.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    clock = Clock.systemUTC();
    SessionCrypto crypto =
        new SessionCrypto(
            ring("locator", (byte) 1, "HmacSHA256", Duration.ofMinutes(5)),
            ring("csrf", (byte) 2, "HmacSHA256", Duration.ofMinutes(5)),
            ring("refresh", (byte) 3, "AES", Duration.ofHours(1)));
    sessions = new RedisBffSessionRepository(uri(), crypto, clock);
    sessions.connection().sync().flushall();
  }

  @AfterEach
  void close() {
    sessions.close();
  }

  @Test
  void loginAndTenantSwitchRotateAtomicallyAndErasureUsesUserIndex() {
    BrowserSessionGrant preauth = sessions.bootstrap();
    assertThat(sessions.connection().sync().exists(preauth.cookieValue())).isZero();
    assertThat(sessions.load(preauth.cookieValue())).isPresent();
    UUID user = UUID.randomUUID(), family = UUID.randomUUID();
    Instant now = clock.instant();
    BrowserSessionGrant authenticated =
        sessions.rotateAuthenticated(
            preauth.session(),
            user,
            "s".repeat(43),
            family,
            "refresh-1",
            now.plus(Duration.ofDays(7)),
            now.plus(Duration.ofDays(30)));
    assertThat(sessions.load(preauth.cookieValue())).isEmpty();
    BrowserSession active = sessions.load(authenticated.cookieValue()).orElseThrow();
    assertThat(active.refreshCredential()).isEqualTo("refresh-1");
    assertThat(sessions.csrfMatches(active, preauth.csrfToken())).isFalse();
    assertThat(sessions.csrfMatches(active, authenticated.csrfToken())).isTrue();
    UUID tenant = UUID.randomUUID(), membership = UUID.randomUUID();
    BrowserSessionGrant selected =
        sessions.rotateTenant(
            active,
            "refresh-2",
            now.plus(Duration.ofDays(7)),
            now.plus(Duration.ofDays(30)),
            tenant,
            membership);
    assertThat(sessions.load(authenticated.cookieValue())).isEmpty();
    BrowserSession tenantSession = sessions.load(selected.cookieValue()).orElseThrow();
    assertThat(tenantSession.selectedTenantId()).isEqualTo(tenant);
    assertThat(tenantSession.selectedMembershipId()).isEqualTo(membership);
    assertThat(tenantSession.refreshCredential()).isEqualTo("refresh-2");
    sessions.eraseUser(user);
    assertThat(sessions.load(selected.cookieValue())).isEmpty();
  }

  @Test
  void rawCookieNeverAppearsAsRedisKey() {
    BrowserSessionGrant grant = sessions.bootstrap();
    List<String> keys = sessions.connection().sync().keys("*");
    assertThat(keys).noneMatch(k -> k.contains(grant.cookieValue()));
    assertThat(keys).allMatch(k -> k.startsWith("web-bff:session:v1:"));
  }

  @Test
  void mfaPreauthRotationStoresOnlyEncryptedChallengeAndCannotActAsAuthenticatedSession() {
    BrowserSessionGrant preauth = sessions.bootstrap();
    UUID userId = UUID.randomUUID();
    String challenge = "M".repeat(43);

    BrowserSessionGrant mfa =
        sessions.rotateMfaPreauth(
            preauth.session(), userId, challenge, clock.instant().plus(Duration.ofMinutes(5)));

    assertThat(sessions.load(preauth.cookieValue())).isEmpty();
    BrowserSession loaded = sessions.load(mfa.cookieValue()).orElseThrow();
    assertThat(loaded.mode()).isEqualTo(BrowserSessionMode.MFA_PREAUTH);
    assertThat(loaded.userId()).isEqualTo(userId);
    assertThat(loaded.mfaChallenge()).isEqualTo(challenge);
    assertThat(loaded.refreshCredential()).isNull();
    assertThat(loaded.authenticated()).isFalse();
    Map<String, String> raw = sessions.connection().sync().hgetall(loaded.locator());
    assertThat(raw).doesNotContainValue(challenge);
    assertThat(raw).containsKeys("mfa_key_id", "mfa_nonce", "mfa_ciphertext");
  }

  private FileBackedKeyRing ring(String name, byte fill, String algorithm, Duration stale)
      throws Exception {
    Path p = temp.resolve(name + ".properties");
    byte[] key = new byte[32];
    Arrays.fill(key, fill);
    Files.writeString(
        p, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(key) + "\n");
    return new FileBackedKeyRing(p, algorithm, 32, clock, stale);
  }

  private static String uri() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }
}
