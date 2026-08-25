package com.sajtech.webbff.infrastructure.quota;

import static org.assertj.core.api.Assertions.*;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.OidcQuotaPort;
import com.sajtech.webbff.configuration.WebBffProperties;
import com.sajtech.webbff.infrastructure.security.keyring.FileBackedKeyRing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class RedisOidcQuotaIntegrationTest {
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
  private RedisOidcQuota quota;
  private Path keyPath;
  private Path hostTime;

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
    Clock clock = Clock.systemUTC();
    keyPath = temp.resolve("quota.properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    Files.writeString(
        keyPath, "active_key_id=q1\nkey.q1=" + Base64.getEncoder().encodeToString(key) + "\n");
    hostTime = temp.resolve("host-time");
    Files.writeString(hostTime, "synchronized\n");
    FileBackedKeyRing ring =
        new FileBackedKeyRing(keyPath, "HmacSHA256", 32, clock, Duration.ofMinutes(5));
    quota =
        new RedisOidcQuota(
            redisUri(),
            new OidcQuotaKeyEncoder(ring),
            new OidcClockSafetyGuard(clock),
            new OidcHostTimeHealth(hostTime),
            new WebBffProperties.OidcQuota(10000, 1000, 30, hostTime),
            new SimpleMeterRegistry());
    quota.connection().sync().flushall();
  }

  @AfterEach
  void close() {
    quota.close();
  }

  @Test
  void startQuotaUsesExactAddressAsHardIdentityAndNeverStoresRawAddress() {
    byte[] first = new byte[] {(byte) 203, 0, 113, 10};
    byte[] second = new byte[] {(byte) 203, 0, 113, 11};
    for (int index = 0; index < 60; index++) {
      quota.consume(OidcQuotaPort.Operation.OIDC_START, first);
      quota.consume(OidcQuotaPort.Operation.OIDC_START, second);
    }

    assertThatThrownBy(() -> quota.consume(OidcQuotaPort.Operation.OIDC_START, first))
        .isInstanceOfSatisfying(
            BffException.class,
            exception -> assertThat(exception.error()).isEqualTo(BffError.RATE_LIMITED));
    assertThat(quota.connection().sync().keys("*"))
        .allMatch(key -> !key.contains("203.0.113.10") && !key.contains("203.0.113.11"));
  }

  @Test
  void redisTimeSkewFailsClosedWithDistinctTimeHealthError() throws Exception {
    quota.close();
    quota =
        quota(
            Clock.offset(Clock.systemUTC(), Duration.ofSeconds(10)),
            new WebBffProperties.OidcQuota(10000, 1000, 30, hostTime));

    assertThatThrownBy(
            () ->
                quota.consume(
                    OidcQuotaPort.Operation.OIDC_CALLBACK, new byte[] {(byte) 203, 0, 113, 10}))
        .isInstanceOfSatisfying(
            BffException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(BffError.QUOTA_TIME_SOURCE_UNHEALTHY));
  }

  @Test
  void evictionPolicyAndNewBucketCardinalityFailClosedAsCapacityErrors() throws Exception {
    quota.connection().sync().configSet("maxmemory-policy", "allkeys-lru");
    try {
      assertCapacityFailure();
    } finally {
      quota.connection().sync().configSet("maxmemory-policy", "noeviction");
    }

    quota.close();
    quota = quota(Clock.systemUTC(), new WebBffProperties.OidcQuota(1, 1000, 30, hostTime));
    assertCapacityFailure();
  }

  private void assertCapacityFailure() {
    assertThatThrownBy(
            () ->
                quota.consume(
                    OidcQuotaPort.Operation.OIDC_START, new byte[] {(byte) 203, 0, 113, 20}))
        .isInstanceOfSatisfying(
            BffException.class,
            exception ->
                assertThat(exception.error()).isEqualTo(BffError.QUOTA_CAPACITY_UNHEALTHY));
  }

  private RedisOidcQuota quota(Clock clock, WebBffProperties.OidcQuota policy) throws Exception {
    FileBackedKeyRing ring =
        new FileBackedKeyRing(keyPath, "HmacSHA256", 32, clock, Duration.ofMinutes(5));
    return new RedisOidcQuota(
        redisUri(),
        new OidcQuotaKeyEncoder(ring),
        new OidcClockSafetyGuard(clock),
        new OidcHostTimeHealth(hostTime),
        policy,
        new SimpleMeterRegistry());
  }

  private static String redisUri() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }
}
