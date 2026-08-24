package com.sajtech.identity.infrastructure.quota;

import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class RedisSemanticQuota implements SemanticQuotaPort, AutoCloseable {
  private static final Duration BUDGET = Duration.ofMillis(75);
  private static final String SCRIPT =
"""
local app_now=tonumber(ARGV[1])
local t=redis.call('TIME')
local redis_now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
if math.abs(redis_now-app_now)>2000 then return {'TIME_UNHEALTHY'} end
local max_active=tonumber(ARGV[2])
local max_alloc=tonumber(ARGV[3])
if max_active<=0 or max_alloc<=0 then return {'CAPACITY_UNHEALTHY'} end
local active_key=KEYS[1]
local alloc_key=KEYS[2]
local index_key=KEYS[3]
local dim_count=tonumber(ARGV[4])
local new_count=0
for i=1,dim_count do
  local key=KEYS[3+i]
  if redis.call('EXISTS',key)==0 then new_count=new_count+1 end
end
local active=tonumber(redis.call('GET',active_key) or '0')
if active+new_count>max_active then return {'CAPACITY_UNHEALTHY'} end
local window_start=tonumber(redis.call('HGET',alloc_key,'window_start') or tostring(redis_now))
local window_count=tonumber(redis.call('HGET',alloc_key,'count') or '0')
if redis_now-window_start>=60000 then window_start=redis_now window_count=0 end
if window_count+new_count>max_alloc then return {'CAPACITY_UNHEALTHY'} end
local proposed={}
local any_denied=false
local arg=5
for i=1,dim_count do
  local key=KEYS[3+i]
  local hard=tonumber(ARGV[arg]); local cap=tonumber(ARGV[arg+1]); local interval=tonumber(ARGV[arg+2]); local horizon=tonumber(ARGV[arg+3]); arg=arg+4
  local tokens=tonumber(redis.call('HGET',key,'tokens') or tostring(cap*1000000))
  local last=tonumber(redis.call('HGET',key,'last_ms') or tostring(redis_now))
  local elapsed=math.max(0,redis_now-last)
  local accepted_last=math.max(last,redis_now)
  local refill=math.floor(elapsed*1000000/interval)
  tokens=math.min(cap*1000000,tokens+refill)
  if hard==1 and tokens<1000000 then any_denied=true end
  proposed[i]={key=key,hard=hard,cap=cap,interval=interval,horizon=horizon,tokens=tokens,last=accepted_last}
end
if any_denied then return {'QUOTA_EXCEEDED'} end
for i=1,dim_count do
  local p=proposed[i]
  local existed=redis.call('EXISTS',p.key)
  local tokens=p.tokens
  if p.hard==1 then tokens=tokens-1000000 elseif tokens>=1000000 then tokens=tokens-1000000 end
  redis.call('HSET',p.key,'tokens',tokens,'last_ms',p.last,'last_used_ms',redis_now,'capacity',p.cap,'interval_ms',p.interval,'cleanup_horizon_ms',p.horizon)
  redis.call('ZADD',index_key,redis_now,p.key)
  if existed==0 then redis.call('INCR',active_key) end
end
redis.call('HSET',alloc_key,'window_start',window_start,'count',window_count+new_count)
return {'ALLOWED',tostring(redis_now),tostring(active+new_count),tostring(window_count+new_count)}
""";
  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final QuotaKeyEncoder keys;
  private final ClockSafetyGuard clockGuard;
  private final HostTimeHealth hostTime;
  private final int maxActiveBuckets;
  private final int maxNewBucketsPerMinute;
  private final int minimumMemoryHeadroomPercent;

  public RedisSemanticQuota(
      String redisUri,
      QuotaKeyEncoder keys,
      ClockSafetyGuard clockGuard,
      HostTimeHealth hostTime,
      int maxActiveBuckets,
      int maxNewBucketsPerMinute,
      int minimumMemoryHeadroomPercent) {
    if (maxActiveBuckets <= 0
        || maxNewBucketsPerMinute <= 0
        || minimumMemoryHeadroomPercent < 30
        || minimumMemoryHeadroomPercent >= 100)
      throw new RegistrationException(
          RegistrationError.QUOTA_CAPACITY_UNHEALTHY, "Quota capacity policy is not configured");
    RedisURI uri = RedisURI.create(redisUri);
    uri.setTimeout(BUDGET);
    client = RedisClient.create(uri);
    connection = client.connect();
    this.keys = keys;
    this.clockGuard = clockGuard;
    this.hostTime = hostTime;
    this.maxActiveBuckets = maxActiveBuckets;
    this.maxNewBucketsPerMinute = maxNewBucketsPerMinute;
    this.minimumMemoryHeadroomPercent = minimumMemoryHeadroomPercent;
  }

  @Override
  public void consume(QuotaRequest request) {
    long started = System.nanoTime();
    long appNow = clockGuard.requireHealthy(hostTime.synchronizedHealthy());
    requireMemoryHeadroom(started);
    QuotaKeyEncoder.EncodedKeys encoded;
    try {
      encoded = keys.encode(request.operation(), request.contact(), request.clientAddress());
    } catch (IllegalArgumentException exception) {
      throw new RegistrationException(
          RegistrationError.QUOTA_UNAVAILABLE, "Trusted client address is unavailable", exception);
    }
    Policy policy = Policy.forOperation(request.operation());
    List<String> redisKeys = new ArrayList<>();
    redisKeys.add("identity:quota:v1:active-count");
    redisKeys.add("identity:quota:v1:allocation-window");
    redisKeys.add("identity:quota:v1:index");
    List<String> argv = new ArrayList<>();
    argv.add(Long.toString(appNow));
    argv.add(Integer.toString(maxActiveBuckets));
    argv.add(Integer.toString(maxNewBucketsPerMinute));
    List<Dimension> dims = new ArrayList<>();
    if (policy.contact() != null) {
      dims.add(new Dimension(encoded.contactKey(), true, policy.contact()));
    }
    dims.add(new Dimension(encoded.exactIpKey(), true, policy.exactIp()));
    dims.add(new Dimension(encoded.aggregateNetworkKey(), false, policy.aggregate()));
    argv.add(Integer.toString(dims.size()));
    for (Dimension d : dims) {
      redisKeys.add(d.key());
      argv.add(d.hard() ? "1" : "0");
      argv.add(Integer.toString(d.policy().capacity()));
      argv.add(Long.toString(d.policy().refillIntervalMs()));
      argv.add(Long.toString(d.policy().cleanupHorizonMs()));
    }
    try {
      Object result =
          connection
              .sync()
              .eval(
                  SCRIPT,
                  ScriptOutputType.MULTI,
                  redisKeys.toArray(String[]::new),
                  argv.toArray(String[]::new));
      if (elapsedMs(started) > BUDGET.toMillis()) throw unavailable(null);
      String code = ((List<?>) result).getFirst().toString();
      switch (code) {
        case "ALLOWED" -> {
          return;
        }
        case "QUOTA_EXCEEDED" ->
            throw new RegistrationException(
                RegistrationError.QUOTA_EXCEEDED, "Semantic quota exceeded");
        case "TIME_UNHEALTHY" ->
            throw new RegistrationException(
                RegistrationError.QUOTA_TIME_SOURCE_UNHEALTHY, "Quota time source is unavailable");
        case "CAPACITY_UNHEALTHY" ->
            throw new RegistrationException(
                RegistrationError.QUOTA_CAPACITY_UNHEALTHY, "Quota capacity is unavailable");
        default -> throw unavailable(null);
      }
    } catch (RegistrationException exception) {
      throw exception;
    } catch (RedisConnectionException | RedisCommandExecutionException exception) {
      throw unavailable(exception);
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private void requireMemoryHeadroom(long started) {
    try {
      String info = connection.sync().info("memory");
      if (elapsedMs(started) > BUDGET.toMillis()) throw unavailable(null);
      long used = value(info, "used_memory");
      long max = value(info, "maxmemory");
      if (max <= 0 || used < 0 || used * 100L > max * (100L - minimumMemoryHeadroomPercent))
        throw new RegistrationException(
            RegistrationError.QUOTA_CAPACITY_UNHEALTHY, "Quota capacity is unavailable");
    } catch (RegistrationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private static long value(String info, String name) {
    for (String line : info.split("\r?\n")) {
      if (line.startsWith(name + ":")) {
        try {
          return Long.parseLong(line.substring(name.length() + 1).trim());
        } catch (NumberFormatException e) {
          return -1;
        }
      }
    }
    return -1;
  }

  private static long elapsedMs(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static RegistrationException unavailable(Throwable cause) {
    return cause == null
        ? new RegistrationException(
            RegistrationError.QUOTA_UNAVAILABLE, "Semantic quota dependency is unavailable")
        : new RegistrationException(
            RegistrationError.QUOTA_UNAVAILABLE, "Semantic quota dependency is unavailable", cause);
  }

  StatefulRedisConnection<String, String> connection() {
    return connection;
  }

  public boolean connectivityHealthy() {
    try {
      return "PONG".equals(connection.sync().ping());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }

  private record Bucket(int capacity, long refillIntervalMs, long cleanupHorizonMs) {}

  private record Policy(Bucket contact, Bucket exactIp, Bucket aggregate) {
    static Policy forOperation(QuotaOperation operation) {
      return switch (operation) {
        case REGISTER ->
            new Policy(
                new Bucket(5, 900000, 86400000),
                new Bucket(60, 5000, 3600000),
                new Bucket(120, 5000, 3600000));
        case RESEND_REGISTRATION_VERIFICATION ->
            new Policy(
                new Bucket(5, 600000, 7200000),
                new Bucket(60, 5000, 3600000),
                new Bucket(120, 5000, 3600000));
        case CONFIRM_REGISTRATION ->
            new Policy(null, new Bucket(120, 500, 1800000), new Bucket(240, 500, 1800000));
        case REQUEST_PASSWORD_RECOVERY ->
            new Policy(
                new Bucket(5, 600000, 7200000),
                new Bucket(30, 10000, 3600000),
                new Bucket(60, 10000, 3600000));
        case CONFIRM_PASSWORD_RECOVERY ->
            new Policy(
                new Bucket(10, 60000, 1800000),
                new Bucket(60, 1000, 1800000),
                new Bucket(120, 1000, 1800000));
      };
    }
  }

  private record Dimension(String key, boolean hard, Bucket policy) {}
}
