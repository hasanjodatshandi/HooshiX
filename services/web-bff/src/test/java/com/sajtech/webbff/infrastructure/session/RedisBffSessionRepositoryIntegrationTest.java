package com.sajtech.webbff.infrastructure.session;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.application.BffError;
import com.sajtech.webbff.application.BffException;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.infrastructure.security.SessionCrypto;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import io.lettuce.core.RedisCommandTimeoutException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
  private SimpleMeterRegistry meters;

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
    meters = new SimpleMeterRegistry();
    sessions = new RedisBffSessionRepository(uri(), crypto, clock, meters);
    sessions.connection().sync().flushall();
  }

  @AfterEach
  void close() {
    sessions.close();
    meters.close();
  }

  @Test
  void delayedSessionWriteFailsClosedAtCommandTimeoutWithoutApplicationRetry() throws Exception {
    sessions.bootstrap();
    assertThat(sessions.connection().getTimeout()).isEqualTo(Duration.ofMillis(75));
    assertThat(REDIS.execInContainer("redis-cli", "CLIENT", "PAUSE", "300", "ALL").getExitCode())
        .isZero();

    assertThatThrownBy(sessions::bootstrap)
        .isInstanceOfSatisfying(
            BffException.class,
            error -> assertThat(error.error()).isEqualTo(BffError.DEPENDENCY_UNAVAILABLE))
        .hasCauseInstanceOf(RedisCommandTimeoutException.class);

    assertThat(
            meters
                .get("web_bff.redis.duration")
                .tags("operation", "create", "outcome", "timeout")
                .timer()
                .count())
        .isEqualTo(1);
    // A timed-out write may still execute; no grant was returned and no retry is made.
    assertThat(
            meters
                .get("web_bff.redis.duration")
                .tags("operation", "create", "outcome", "ok")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void closedRedisConnectionNeverBecomesMissingSessionOrSuccessfulMutation() {
    var preauth = sessions.bootstrap();
    UUID userId = UUID.randomUUID();
    sessions.connection().close();

    for (org.assertj.core.api.ThrowableAssert.ThrowingCallable operation :
        List.<org.assertj.core.api.ThrowableAssert.ThrowingCallable>of(
            sessions::bootstrap,
            () -> sessions.load(preauth.cookieValue()),
            () -> sessions.touch(preauth.session()),
            () ->
                sessions.rotateMfaPreauth(
                    preauth.session(), userId, "M".repeat(43), clock.instant().plusSeconds(300)),
            () ->
                sessions.rotateAuthenticated(
                    preauth.session(),
                    userId,
                    "s".repeat(43),
                    UUID.randomUUID(),
                    "refresh-canary",
                    clock.instant().plusSeconds(600),
                    clock.instant().plusSeconds(1200)),
            () -> sessions.destroy(preauth.session()),
            () -> sessions.eraseUser(userId))) {
      assertThatThrownBy(operation)
          .isInstanceOfSatisfying(
              BffException.class,
              error -> assertThat(error.error()).isEqualTo(BffError.DEPENDENCY_UNAVAILABLE));
    }
    assertThat(meters.getMeters())
        .allSatisfy(
            meter -> {
              assertThat(meter.getId().getTags())
                  .extracting(io.micrometer.core.instrument.Tag::getKey)
                  .containsExactlyInAnyOrder("operation", "outcome");
              assertThat(meter.getId().getTag("operation"))
                  .isIn(
                      "create",
                      "load",
                      "touch",
                      "rotate_mfa",
                      "rotate",
                      "destroy_load",
                      "erase_scan");
              assertThat(meter.getId().getTag("outcome")).isIn("ok", "unavailable");
            });
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
