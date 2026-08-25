package com.sajtech.webbff.infrastructure.session;

import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.BrowserSessionPort;
import com.sajtech.webbff.infrastructure.security.SessionCrypto;
import com.sajtech.webbff.infrastructure.security.SessionCrypto.EncryptedValue;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

public final class RedisBffSessionRepository implements BrowserSessionPort, AutoCloseable {
  private static final Duration PREAUTH = Duration.ofMinutes(10),
      IDLE = Duration.ofDays(7),
      ABSOLUTE = Duration.ofDays(30),
      TOUCH = Duration.ofMinutes(5);
  private static final String CREATE =
"""
if redis.call('EXISTS',KEYS[1])==1 then return 0 end
for i=2,#ARGV,2 do redis.call('HSET',KEYS[1],ARGV[i],ARGV[i+1]) end
redis.call('PEXPIREAT',KEYS[1],ARGV[1]); local idx=redis.call('HGET',KEYS[1],'user_index_key'); if idx and idx~='' then local absolute=tonumber(redis.call('HGET',KEYS[1],'absolute_expires_at') or ARGV[1]);redis.call('SADD',idx,KEYS[1]);redis.call('PEXPIREAT',idx,absolute) end; return 1
""";
  private static final String ROTATE =
"""
if redis.call('EXISTS',KEYS[1])==0 then return 0 end
if redis.call('EXISTS',KEYS[2])==1 then return -1 end
local oldidx=redis.call('HGET',KEYS[1],'user_index_key'); if oldidx and oldidx~='' then redis.call('SREM',oldidx,KEYS[1]) end
for i=2,#ARGV,2 do redis.call('HSET',KEYS[2],ARGV[i],ARGV[i+1]) end
redis.call('PEXPIREAT',KEYS[2],ARGV[1]);local newidx=redis.call('HGET',KEYS[2],'user_index_key');if newidx and newidx~='' then local absolute=tonumber(redis.call('HGET',KEYS[2],'absolute_expires_at') or ARGV[1]);redis.call('SADD',newidx,KEYS[2]);redis.call('PEXPIREAT',newidx,absolute) end
redis.call('DEL',KEYS[1]);return 1
""";
  private static final String TOUCH_SCRIPT =
"""
if redis.call('EXISTS',KEYS[1])==0 then return 0 end
local last=tonumber(redis.call('HGET',KEYS[1],'last_seen_at') or '0');local now=tonumber(ARGV[1]);if now-last<300000 then return 1 end
local absolute=tonumber(redis.call('HGET',KEYS[1],'absolute_expires_at') or '0');local idle=math.min(now+604800000,absolute);if idle<=now then redis.call('DEL',KEYS[1]);return 0 end
redis.call('HSET',KEYS[1],'last_seen_at',now,'idle_expires_at',idle);redis.call('PEXPIREAT',KEYS[1],idle);local idx=redis.call('HGET',KEYS[1],'user_index_key');if idx and idx~='' then redis.call('PEXPIREAT',idx,absolute) end;return 1
""";
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final SessionCrypto crypto;
  private final Clock clock;
  private final MeterRegistry meters;

  public RedisBffSessionRepository(String uri, SessionCrypto crypto, Clock clock) {
    this(uri, crypto, clock, new SimpleMeterRegistry());
  }

  public RedisBffSessionRepository(
      String uri, SessionCrypto crypto, Clock clock, MeterRegistry meters) {
    RedisURI redis = RedisURI.create(uri);
    redis.setTimeout(Duration.ofMillis(75));
    client = RedisClient.create(redis);
    connection = client.connect();
    this.crypto = Objects.requireNonNull(crypto);
    this.clock = Objects.requireNonNull(clock);
    this.meters = Objects.requireNonNull(meters);
  }

  public BrowserSessionGrant bootstrap() {
    Instant now = clock.instant(), absolute = now.plus(PREAUTH);
    var opaque = crypto.issueSessionToken();
    var csrf = crypto.issueCsrf();
    Map<String, String> fields =
        base(
            BrowserSessionMode.PREAUTH,
            null,
            null,
            null,
            null,
            null,
            null,
            csrf.keyId(),
            csrf.digestHex(),
            now,
            absolute,
            absolute,
            "");
    create(opaque.locator(), fields, absolute);
    return new BrowserSessionGrant(
        opaque.cookieValue(), csrf.clear(), decode(opaque.locator(), fields, null));
  }

  public Optional<BrowserSession> load(String cookie) {
    final String locator;
    try {
      locator = crypto.locatorFromCookie(cookie);
    } catch (RuntimeException e) {
      return Optional.empty();
    }
    Map<String, String> f = connection.sync().hgetall(locator);
    if (f.isEmpty()) return Optional.empty();
    Instant now = clock.instant(),
        idle = time(f, "idle_expires_at"),
        absolute = time(f, "absolute_expires_at");
    if (!now.isBefore(idle) || !now.isBefore(absolute)) {
      destroyLocator(locator, f);
      return Optional.empty();
    }
    String refresh = null;
    if (f.containsKey("refresh_ciphertext")) {
      try {
        refresh =
            crypto.decryptRefresh(
                locator,
                new EncryptedValue(
                    req(f, "refresh_key_id"),
                    req(f, "refresh_nonce"),
                    req(f, "refresh_ciphertext")));
      } catch (RuntimeException e) {
        destroyLocator(locator, f);
        return Optional.empty();
      }
    }
    return Optional.of(decode(locator, f, refresh));
  }

  public boolean csrfMatches(BrowserSession session, String clear) {
    return session != null
        && crypto.csrfMatches(clear, session.csrfKeyId(), session.csrfDigestHex());
  }

  public boolean touch(BrowserSession s) {
    Long result =
        connection
            .sync()
            .eval(
                TOUCH_SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {s.locator()},
                Long.toString(clock.millis()));
    return result != null && result == 1L;
  }

  public BrowserSessionGrant rotateAuthenticated(
      BrowserSession old,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant identityIdle,
      Instant identityAbsolute) {
    if (old == null
        || userId == null
        || identitySessionId == null
        || refreshFamilyId == null
        || refreshCredential == null)
      throw new IllegalArgumentException("Authenticated session is incomplete");
    Instant now = clock.instant(),
        absolute = min(now.plus(ABSOLUTE), identityAbsolute),
        idle = min(now.plus(IDLE), min(identityIdle, absolute));
    return rotate(
        old,
        userId,
        identitySessionId,
        refreshFamilyId,
        refreshCredential,
        null,
        null,
        BrowserSessionMode.AUTHENTICATED_ONBOARDING,
        now,
        idle,
        absolute);
  }

  public BrowserSessionGrant rotateAuthenticatedTenant(
      BrowserSession old,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      Instant identityIdle,
      Instant identityAbsolute,
      UUID tenantId,
      UUID membershipId) {
    if (old == null
        || userId == null
        || identitySessionId == null
        || refreshFamilyId == null
        || refreshCredential == null
        || tenantId == null
        || membershipId == null)
      throw new IllegalArgumentException("Tenant-authenticated session is incomplete");
    Instant now = clock.instant(),
        absolute = min(now.plus(ABSOLUTE), identityAbsolute),
        idle = min(now.plus(IDLE), min(identityIdle, absolute));
    return rotate(
        old,
        userId,
        identitySessionId,
        refreshFamilyId,
        refreshCredential,
        tenantId,
        membershipId,
        BrowserSessionMode.TENANT_AUTHENTICATED,
        now,
        idle,
        absolute);
  }

  public BrowserSessionGrant rotateTenant(
      BrowserSession old,
      String rotatedRefresh,
      Instant identityIdle,
      Instant identityAbsolute,
      UUID tenantId,
      UUID membershipId) {
    if (old == null
        || !old.authenticated()
        || rotatedRefresh == null
        || tenantId == null
        || membershipId == null)
      throw new IllegalArgumentException("Tenant session rotation is incomplete");
    Instant now = clock.instant(),
        absolute = min(old.absoluteExpiresAt(), identityAbsolute),
        idle = min(now.plus(IDLE), min(identityIdle, absolute));
    return rotate(
        old,
        old.userId(),
        old.identitySessionId(),
        old.refreshFamilyId(),
        rotatedRefresh,
        tenantId,
        membershipId,
        BrowserSessionMode.TENANT_AUTHENTICATED,
        now,
        idle,
        absolute);
  }

  @Override
  public BrowserSessionGrant rotateSecurityState(
      BrowserSession old, String rotatedRefresh, Instant identityIdle, Instant identityAbsolute) {
    if (old == null || !old.authenticated() || rotatedRefresh == null)
      throw new IllegalArgumentException("Security-state rotation is incomplete");
    Instant now = clock.instant(),
        absolute = min(old.absoluteExpiresAt(), identityAbsolute),
        idle = min(now.plus(IDLE), min(identityIdle, absolute));
    return rotate(
        old,
        old.userId(),
        old.identitySessionId(),
        old.refreshFamilyId(),
        rotatedRefresh,
        old.selectedTenantId(),
        old.selectedMembershipId(),
        old.mode(),
        now,
        idle,
        absolute);
  }

  public void destroy(BrowserSession session) {
    if (session != null) {
      Map<String, String> f =
          timed("destroy_load", () -> connection.sync().hgetall(session.locator()));
      destroyLocator(session.locator(), f);
    }
  }

  public void eraseUser(UUID userId) {
    if (userId == null) return;
    for (String keyId : crypto.locatorKeyIds()) {
      String index = crypto.userSessionIndex(keyId, userId);
      io.lettuce.core.ScanCursor cursor = io.lettuce.core.ScanCursor.INITIAL;
      do {
        var page =
            connection.sync().sscan(index, cursor, io.lettuce.core.ScanArgs.Builder.limit(64));
        for (String locator : page.getValues()) connection.sync().del(locator);
        cursor = page;
      } while (!cursor.isFinished());
      connection.sync().del(index);
    }
  }

  private BrowserSessionGrant rotate(
      BrowserSession old,
      UUID userId,
      String identitySessionId,
      UUID refreshFamilyId,
      String refreshCredential,
      UUID tenant,
      UUID membership,
      BrowserSessionMode mode,
      Instant now,
      Instant idle,
      Instant absolute) {
    if (!now.isBefore(idle) || !idle.isAfter(now) || absolute.isAfter(now.plus(ABSOLUTE)))
      throw new IllegalArgumentException("Session lifetime is invalid");
    var opaque = crypto.issueSessionToken();
    var csrf = crypto.issueCsrf();
    var encrypted = crypto.encryptRefresh(opaque.locator(), refreshCredential);
    String locatorKeyId = opaque.locator().split(":", 5)[3];
    String userIndex = crypto.userSessionIndex(locatorKeyId, userId);
    Map<String, String> fields =
        base(
            mode,
            userId,
            identitySessionId,
            refreshFamilyId,
            tenant,
            membership,
            encrypted,
            csrf.keyId(),
            csrf.digestHex(),
            now,
            idle,
            absolute,
            userIndex);
    String[] argv = args(fields, idle);
    Long result =
        connection
            .sync()
            .eval(
                ROTATE,
                ScriptOutputType.INTEGER,
                new String[] {old.locator(), opaque.locator()},
                argv);
    if (result == null || result != 1L)
      throw new IllegalStateException("BFF session rotation failed");
    return new BrowserSessionGrant(
        opaque.cookieValue(), csrf.clear(), decode(opaque.locator(), fields, refreshCredential));
  }

  private void create(String locator, Map<String, String> fields, Instant expiry) {
    Long result =
        connection
            .sync()
            .eval(CREATE, ScriptOutputType.INTEGER, new String[] {locator}, args(fields, expiry));
    if (result == null || result != 1L)
      throw new IllegalStateException("BFF session creation failed");
  }

  private static String[] args(Map<String, String> fields, Instant expiry) {
    String[] a = new String[1 + fields.size() * 2];
    a[0] = Long.toString(expiry.toEpochMilli());
    int i = 1;
    for (var e : fields.entrySet()) {
      a[i++] = e.getKey();
      a[i++] = e.getValue();
    }
    return a;
  }

  private static Map<String, String> base(
      BrowserSessionMode mode,
      UUID user,
      String identitySession,
      UUID family,
      UUID tenant,
      UUID membership,
      EncryptedValue encrypted,
      String csrfKey,
      String csrfDigest,
      Instant created,
      Instant idle,
      Instant absolute,
      String userIndex) {
    Map<String, String> f = new LinkedHashMap<>();
    f.put("mode", mode.name());
    f.put("user_id", user == null ? "" : user.toString());
    f.put("identity_session_id", identitySession == null ? "" : identitySession);
    f.put("refresh_family_id", family == null ? "" : family.toString());
    f.put("selected_tenant_id", tenant == null ? "" : tenant.toString());
    f.put("selected_membership_id", membership == null ? "" : membership.toString());
    f.put("csrf_key_id", csrfKey);
    f.put("csrf_digest", csrfDigest);
    f.put("created_at", Long.toString(created.toEpochMilli()));
    f.put("last_seen_at", Long.toString(created.toEpochMilli()));
    f.put("idle_expires_at", Long.toString(idle.toEpochMilli()));
    f.put("absolute_expires_at", Long.toString(absolute.toEpochMilli()));
    f.put("user_index_key", userIndex);
    if (encrypted != null) {
      f.put("refresh_key_id", encrypted.keyId());
      f.put("refresh_nonce", encrypted.nonce());
      f.put("refresh_ciphertext", encrypted.ciphertext());
    }
    return f;
  }

  private static BrowserSession decode(String locator, Map<String, String> f, String refresh) {
    return new BrowserSession(
        locator,
        BrowserSessionMode.valueOf(req(f, "mode")),
        uuid(f.get("user_id")),
        blank(f.get("identity_session_id")),
        uuid(f.get("refresh_family_id")),
        refresh,
        uuid(f.get("selected_tenant_id")),
        uuid(f.get("selected_membership_id")),
        req(f, "csrf_key_id"),
        req(f, "csrf_digest"),
        time(f, "created_at"),
        time(f, "last_seen_at"),
        time(f, "idle_expires_at"),
        time(f, "absolute_expires_at"));
  }

  private void destroyLocator(String locator, Map<String, String> f) {
    String idx = f == null ? null : f.get("user_index_key");
    if (idx != null && !idx.isBlank()) connection.sync().srem(idx, locator);
    connection.sync().del(locator);
  }

  private static String req(Map<String, String> f, String k) {
    String v = f.get(k);
    if (v == null || v.isBlank())
      throw new IllegalStateException("BFF session state is incomplete");
    return v;
  }

  private static String blank(String v) {
    return v == null || v.isBlank() ? null : v;
  }

  private static UUID uuid(String v) {
    try {
      return v == null || v.isBlank() ? null : UUID.fromString(v);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("BFF session UUID is invalid", e);
    }
  }

  private static Instant time(Map<String, String> f, String k) {
    try {
      return Instant.ofEpochMilli(Long.parseLong(req(f, k)));
    } catch (RuntimeException e) {
      throw new IllegalStateException("BFF session timestamp is invalid", e);
    }
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private <T> T timed(String operation, Supplier<T> work) {
    long started = System.nanoTime();
    String outcome = "ok";
    try {
      return work.get();
    } catch (RuntimeException e) {
      outcome = "error";
      throw e;
    } finally {
      Timer.builder("web_bff.redis.duration")
          .tag("operation", operation)
          .tag("outcome", outcome)
          .register(meters)
          .record(System.nanoTime() - started, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
  }

  public boolean connectivityHealthy() {
    try {
      return "PONG".equals(connection.sync().ping());
    } catch (RuntimeException e) {
      return false;
    }
  }

  StatefulRedisConnection<String, String> connection() {
    return connection;
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }
}
