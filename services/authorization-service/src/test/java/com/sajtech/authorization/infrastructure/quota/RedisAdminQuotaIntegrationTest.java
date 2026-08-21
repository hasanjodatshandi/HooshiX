package com.sajtech.authorization.infrastructure.quota;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.ActorContext;
import com.sajtech.authorization.infrastructure.security.keyring.FileBackedKeyRing;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class RedisAdminQuotaIntegrationTest {
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

  @BeforeAll
  static void start() {
    REDIS.start();
  }

  @AfterAll
  static void stop() {
    REDIS.stop();
  }

  @Test
  void deniedRequestMigratesDepletedBudgetToNewHmacKeyWithoutReset() throws Exception {
    Path ringPath = temp.resolve("quota.properties");
    byte[] one = filled((byte) 1), two = filled((byte) 2);
    write(ringPath, "k1", one, two);
    Clock clock = Clock.systemUTC();
    FileBackedKeyRing ring =
        new FileBackedKeyRing(ringPath, "HmacSHA256", 32, clock, Duration.ofMinutes(5));
    try (RedisAdminQuota quota =
        new RedisAdminQuota(
            uri(), ring, new ClockSafetyGuard(clock), () -> true, 10_000, 1_000, 30)) {
      quota.connection().sync().flushall();
      ActorContext actor =
          new ActorContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "s".repeat(43));
      quota.acquire(actor, 1);
      String oldActor =
          quota
              .bucketKeys("actor", actor.userId().toString(), actor.tenantId().toString())
              .getFirst();
      long redisNow = redisNow(quota);
      quota
          .connection()
          .sync()
          .hset(
              oldActor,
              Map.of(
                  "tokens",
                  "0",
                  "last_ms",
                  Long.toString(redisNow + 60_000),
                  "last_used_ms",
                  Long.toString(redisNow),
                  "capacity",
                  "120",
                  "interval_ms",
                  "500",
                  "cleanup_horizon_ms",
                  "3600000"));
      write(ringPath, "k2", one, two);
      ring.refresh();
      List<String> rotated =
          quota.bucketKeys("actor", actor.userId().toString(), actor.tenantId().toString());
      String newActor = rotated.getFirst();
      assertThat(newActor).isNotEqualTo(oldActor);
      assertThatThrownBy(() -> quota.acquire(actor, 1))
          .isInstanceOfSatisfying(
              AuthorizationException.class,
              e -> assertThat(e.error()).isEqualTo(AuthorizationError.QUOTA_EXCEEDED));
      assertThat(quota.connection().sync().exists(oldActor)).isZero();
      assertThat(quota.connection().sync().exists(newActor)).isOne();
      assertThat(Long.parseLong(quota.connection().sync().hget(newActor, "tokens"))).isZero();
      Files.writeString(
          ringPath, "active_key_id=k2\nkey.k2=" + Base64.getEncoder().encodeToString(two) + "\n");
      ring.refresh();
      assertThatThrownBy(() -> quota.acquire(actor, 1))
          .isInstanceOfSatisfying(
              AuthorizationException.class,
              e -> assertThat(e.error()).isEqualTo(AuthorizationError.QUOTA_EXCEEDED));
    }
  }

  @Test
  void redisOutageFailsClosedWithoutFallback() {
    Path missing = temp.resolve("keys.properties");
    byte[] one = filled((byte) 3);
    try {
      Files.writeString(
          missing, "active_key_id=k1\nkey.k1=" + Base64.getEncoder().encodeToString(one) + "\n");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    Clock clock = Clock.systemUTC();
    FileBackedKeyRing ring =
        new FileBackedKeyRing(missing, "HmacSHA256", 32, clock, Duration.ofMinutes(5));
    String unreachable = "redis://127.0.0.1:1";
    assertThatThrownBy(
            () -> {
              try (RedisAdminQuota ignored =
                  new RedisAdminQuota(
                      unreachable, ring, new ClockSafetyGuard(clock), () -> true, 100, 100, 30)) {}
            })
        .isInstanceOf(RuntimeException.class);
  }

  private static void write(Path path, String active, byte[] one, byte[] two) throws Exception {
    Files.writeString(
        path,
        "active_key_id="
            + active
            + "\nkey.k1="
            + Base64.getEncoder().encodeToString(one)
            + "\nkey.k2="
            + Base64.getEncoder().encodeToString(two)
            + "\n");
  }

  private static byte[] filled(byte value) {
    byte[] key = new byte[32];
    Arrays.fill(key, value);
    return key;
  }

  private static long redisNow(RedisAdminQuota quota) {
    List<String> time = quota.connection().sync().time();
    return Long.parseLong(time.get(0).toString()) * 1000
        + Long.parseLong(time.get(1).toString()) / 1000;
  }

  private static String uri() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }
}
