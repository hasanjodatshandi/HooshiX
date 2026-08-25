package com.sajtech.identity.infrastructure.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.MfaQuotaOperation;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationChannel;
import com.sajtech.identity.infrastructure.security.keyring.FileBackedKeyRing;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class RedisSemanticQuotaIntegrationTest {
  private static final int REDIS_PORT = 6379;
  private static final DockerImageName IMAGE =
      DockerImageName.parse(
              "redis:8.2.8-bookworm@sha256:2f7462b9e93e0a7ae2edf3a0a0babc8a4d29f8bfc50849b906b7caaef925edc1")
          .asCompatibleSubstituteFor("redis");
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(IMAGE)
          .withExposedPorts(REDIS_PORT)
          .withCommand(
              "redis-server",
              "--save",
              "",
              "--appendonly",
              "no",
              "--maxmemory",
              "64mb",
              "--maxmemory-policy",
              "noeviction");

  @BeforeAll
  static void startRedis() {
    REDIS.start();
  }

  @AfterAll
  static void stopRedis() {
    REDIS.stop();
  }

  @TempDir Path temp;

  @BeforeEach
  void resetRedis() {
    RedisClient client = RedisClient.create(redisUri());
    try (StatefulRedisConnection<String, String> connection = client.connect()) {
      connection.sync().flushall();
    } finally {
      client.shutdown();
    }
  }

  @Test
  void contactHardGateIsAtomicAndAuthoritativeBucketsHaveNoTtl() throws Exception {
    try (RedisSemanticQuota quota = quota(Clock.systemUTC(), 100, 100)) {
      QuotaRequest request =
          new QuotaRequest(
              QuotaOperation.REGISTER,
              email("person@example.com"),
              new byte[] {(byte) 203, 0, 113, 10});

      for (int i = 0; i < 5; i++) {
        quota.consume(request);
      }
      assertThatThrownBy(() -> quota.consume(request))
          .isInstanceOf(RegistrationException.class)
          .extracting(error -> ((RegistrationException) error).error())
          .isEqualTo(RegistrationError.QUOTA_EXCEEDED);

      RedisClient client = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = client.connect()) {
        var commands = connection.sync();
        var keys = commands.keys("identity:quota:v1:*");
        assertThat(keys).isNotEmpty();
        for (String key : keys) {
          assertThat(commands.ttl(key)).as("authoritative quota key %s", key).isEqualTo(-1L);
        }
      } finally {
        client.shutdown();
      }
    }
  }

  @Test
  void aggregateNetworkPressureNeverBecomesTheSoleHardGate() throws Exception {
    try (RedisSemanticQuota quota = quota(Clock.systemUTC(), 1000, 1000)) {
      for (int host = 1; host <= 121; host++) {
        quota.consume(
            new QuotaRequest(
                QuotaOperation.REGISTER,
                email("person" + host + "@example.com"),
                new byte[] {(byte) 198, 51, 100, (byte) host}));
      }
    }
  }

  @Test
  void exactIpIsASeparateHardGate() throws Exception {
    Clock clock = Clock.systemUTC();
    try (QuotaFixture fixture = quotaFixture(clock, 1000, 1000)) {
      byte[] exact = new byte[] {(byte) 192, 0, 2, 44};
      fixture
          .quota()
          .consume(
              new QuotaRequest(QuotaOperation.REGISTER, email("exact-first@example.com"), exact));
      String exactKey =
          fixture
              .encoder()
              .encode(QuotaOperation.REGISTER, email("exact-next@example.com"), exact)
              .exactIpKey();
      RedisClient client = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = client.connect()) {
        connection
            .sync()
            .hset(
                exactKey,
                java.util.Map.of(
                    "tokens", "0", "last_ms", Long.toString(clock.millis() + 60_000L)));
      } finally {
        client.shutdown();
      }

      assertThatThrownBy(
              () ->
                  fixture
                      .quota()
                      .consume(
                          new QuotaRequest(
                              QuotaOperation.REGISTER, email("exact-next@example.com"), exact)))
          .isInstanceOf(RegistrationException.class)
          .extracting(error -> ((RegistrationException) error).error())
          .isEqualTo(RegistrationError.QUOTA_EXCEEDED);
    }
  }

  @Test
  void redisClockSkewFailsClosedWithoutQuotaDenial() throws Exception {
    Clock staleClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    try (RedisSemanticQuota quota = quota(staleClock, 100, 100)) {
      assertThatThrownBy(
              () ->
                  quota.consume(
                      new QuotaRequest(
                          QuotaOperation.REGISTER,
                          email("skew@example.com"),
                          new byte[] {(byte) 203, 0, 113, 90})))
          .isInstanceOf(RegistrationException.class)
          .extracting(error -> ((RegistrationException) error).error())
          .isEqualTo(RegistrationError.QUOTA_TIME_SOURCE_UNHEALTHY);
    }
  }

  @Test
  void unsafeNewAllocationCapacityFailsBeforeCreatingBuckets() throws Exception {
    try (RedisSemanticQuota quota = quota(Clock.systemUTC(), 2, 100)) {
      assertThatThrownBy(
              () ->
                  quota.consume(
                      new QuotaRequest(
                          QuotaOperation.REGISTER,
                          email("capacity@example.com"),
                          new byte[] {(byte) 203, 0, 113, 91})))
          .isInstanceOf(RegistrationException.class)
          .extracting(error -> ((RegistrationException) error).error())
          .isEqualTo(RegistrationError.QUOTA_CAPACITY_UNHEALTHY);
    }
  }

  @Test
  void loginExactIpIsHardWhileAggregatePressureAloneDoesNotReject() throws Exception {
    Clock clock = Clock.systemUTC();
    byte[] exact = new byte[] {(byte) 192, 0, 2, 88};
    try (LoginQuotaFixture fixture = loginQuotaFixture(clock, 1000, 1000)) {
      fixture.quota().checkSource(exact);
      QuotaKeyEncoder.LoginSourceKeys encoded = fixture.encoder().encodeLoginSource(exact);
      RedisClient client = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = client.connect()) {
        connection
            .sync()
            .hset(
                encoded.exactIpKey(),
                java.util.Map.of(
                    "tokens", "0", "last_ms", Long.toString(clock.millis() + 60_000L)));
      } finally {
        client.shutdown();
      }
      assertThatThrownBy(() -> fixture.quota().checkSource(exact))
          .isInstanceOf(AuthenticationException.class)
          .extracting(error -> ((AuthenticationException) error).error())
          .isEqualTo(AuthenticationError.QUOTA_EXCEEDED);

      RedisClient secondClient = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = secondClient.connect()) {
        connection
            .sync()
            .hset(
                encoded.exactIpKey(),
                java.util.Map.of("tokens", "120000000", "last_ms", Long.toString(clock.millis())));
        connection
            .sync()
            .hset(
                encoded.aggregateNetworkKey(),
                java.util.Map.of(
                    "tokens", "0", "last_ms", Long.toString(clock.millis() + 60_000L)));
      } finally {
        secondClient.shutdown();
      }
      fixture.quota().checkSource(exact);
    }
  }

  @Test
  void loginFailureSubjectPressureIsPostProofAndSuccessfulProofResetRemovesIt() throws Exception {
    CanonicalContact contact = email("login@example.com");
    try (LoginQuotaFixture fixture = loginQuotaFixture(Clock.systemUTC(), 1000, 1000)) {
      for (int i = 0; i < 8; i++) fixture.quota().recordFailure(contact);
      assertThatThrownBy(() -> fixture.quota().recordFailure(contact))
          .isInstanceOf(AuthenticationException.class)
          .extracting(error -> ((AuthenticationException) error).error())
          .isEqualTo(AuthenticationError.QUOTA_EXCEEDED);
      String subjectKey = fixture.encoder().encodeLoginSubject(contact);
      RedisClient client = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = client.connect()) {
        assertThat(connection.sync().ttl(subjectKey)).isEqualTo(-1L);
      } finally {
        client.shutdown();
      }

      fixture.quota().recordSuccess(contact);
      fixture.quota().recordFailure(contact);
    }
  }

  @Test
  void loginRedisClockSkewFailsClosedAsAvailabilityNotQuotaDenial() throws Exception {
    Clock staleClock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
    try (LoginQuotaFixture fixture = loginQuotaFixture(staleClock, 100, 100)) {
      assertThatThrownBy(() -> fixture.quota().checkSource(new byte[] {(byte) 203, 0, 113, 99}))
          .isInstanceOf(AuthenticationException.class)
          .extracting(error -> ((AuthenticationException) error).error())
          .isEqualTo(AuthenticationError.QUOTA_TIME_SOURCE_UNHEALTHY);
    }
  }

  @Test
  void mfaEnrollmentHasIndependentFiveAttemptUserGateWithNoSecurityTtl() throws Exception {
    UUID userId = UUID.randomUUID();
    byte[] address = {(byte) 203, 0, 113, 44};
    try (LoginQuotaFixture fixture = loginQuotaFixture(Clock.systemUTC(), 1000, 1000)) {
      for (int attempt = 0; attempt < 5; attempt++) {
        fixture.quota().consume(MfaQuotaOperation.ENROLL, userId, address);
      }

      assertThatThrownBy(() -> fixture.quota().consume(MfaQuotaOperation.ENROLL, userId, address))
          .isInstanceOf(MfaException.class)
          .extracting(error -> ((MfaException) error).error())
          .isEqualTo(MfaError.QUOTA_EXCEEDED);

      String userKey = fixture.encoder().encodeMfa("MFA_ENROLL", userId, address).userKey();
      RedisClient client = RedisClient.create(redisUri());
      try (StatefulRedisConnection<String, String> connection = client.connect()) {
        assertThat(connection.sync().ttl(userKey)).isEqualTo(-1L);
      } finally {
        client.shutdown();
      }
    }
  }

  @Test
  void recoveryPressureChargesCostTwoToSourceAndFailedSubject() throws Exception {
    byte[] address = {(byte) 198, 51, 100, 19};
    UUID userId = UUID.randomUUID();
    try (LoginQuotaFixture fixture = loginQuotaFixture(Clock.systemUTC(), 1000, 1000)) {
      for (int attempt = 0; attempt < 15; attempt++) {
        fixture.quota().consumeRecoverySource(address);
      }
      assertThatThrownBy(() -> fixture.quota().consumeRecoverySource(address))
          .isInstanceOf(MfaException.class)
          .extracting(error -> ((MfaException) error).error())
          .isEqualTo(MfaError.QUOTA_EXCEEDED);

      for (int attempt = 0; attempt < 3; attempt++) {
        fixture.quota().recordRecoveryFailure(userId);
      }
      assertThatThrownBy(() -> fixture.quota().recordRecoveryFailure(userId))
          .isInstanceOf(MfaException.class)
          .extracting(error -> ((MfaException) error).error())
          .isEqualTo(MfaError.QUOTA_EXCEEDED);
    }
  }

  private LoginQuotaFixture loginQuotaFixture(Clock clock, int maxActive, int maxNewPerMinute)
      throws Exception {
    Path keyRing = temp.resolve("login-quota-" + System.nanoTime() + ".properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 9);
    Files.writeString(
        keyRing, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    FileBackedKeyRing ring =
        new FileBackedKeyRing(keyRing, "HmacSHA256", 32, clock, Duration.ofHours(1));
    QuotaKeyEncoder encoder = new QuotaKeyEncoder(ring);
    RedisLoginQuota quota =
        new RedisLoginQuota(
            redisUri(),
            encoder,
            new ClockSafetyGuard(clock),
            () -> true,
            maxActive,
            maxNewPerMinute,
            30);
    return new LoginQuotaFixture(quota, encoder);
  }

  private record LoginQuotaFixture(RedisLoginQuota quota, QuotaKeyEncoder encoder)
      implements AutoCloseable {
    @Override
    public void close() {
      quota.close();
    }
  }

  private RedisSemanticQuota quota(Clock clock, int maxActive, int maxNewPerMinute)
      throws Exception {
    return quotaFixture(clock, maxActive, maxNewPerMinute).quota();
  }

  private QuotaFixture quotaFixture(Clock clock, int maxActive, int maxNewPerMinute)
      throws Exception {
    Path keyRing = temp.resolve("quota-" + System.nanoTime() + ".properties");
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 7);
    Files.writeString(
        keyRing, "active_key_id=v1\nkey.v1=" + Base64.getEncoder().encodeToString(key) + "\n");
    FileBackedKeyRing ring =
        new FileBackedKeyRing(keyRing, "HmacSHA256", 32, clock, Duration.ofHours(1));
    QuotaKeyEncoder encoder = new QuotaKeyEncoder(ring);
    ClockSafetyGuard guard = new ClockSafetyGuard(clock);
    RedisSemanticQuota quota =
        new RedisSemanticQuota(
            redisUri(), encoder, guard, () -> true, maxActive, maxNewPerMinute, 30);
    return new QuotaFixture(quota, encoder);
  }

  private record QuotaFixture(RedisSemanticQuota quota, QuotaKeyEncoder encoder)
      implements AutoCloseable {
    @Override
    public void close() {
      quota.close();
    }
  }

  private String redisUri() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT);
  }

  private static CanonicalContact email(String value) {
    return new CanonicalContact(RegistrationChannel.EMAIL, value, value);
  }
}
