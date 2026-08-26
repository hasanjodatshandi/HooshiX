package com.sajtech.webbff.infrastructure.quota;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.port.out.OidcQuotaPort;
import com.sajtech.webbff.configuration.WebBffProperties;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;

public final class RedisOidcQuota implements OidcQuotaPort, AutoCloseable {
  private static final Duration BUDGET = Duration.ofMillis(75);
  private static final String SCRIPT =
      """
      local app_now=tonumber(ARGV[1]);local t=redis.call('TIME');
      local redis_now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
      if math.abs(redis_now-app_now)>2000 then return {'TIME_UNHEALTHY'} end
      local max_active=tonumber(ARGV[2]);local max_alloc=tonumber(ARGV[3])
      if max_active<=0 or max_alloc<=0 then return {'CAPACITY_UNHEALTHY'} end
      local new_count=0
      for i=4,5 do if redis.call('EXISTS',KEYS[i])==0 then new_count=new_count+1 end end
      local active=tonumber(redis.call('GET',KEYS[1]) or '0')
      if active+new_count>max_active then return {'CAPACITY_UNHEALTHY'} end
      local window_start=tonumber(redis.call('HGET',KEYS[2],'window_start') or tostring(redis_now))
      local window_count=tonumber(redis.call('HGET',KEYS[2],'count') or '0')
      if redis_now-window_start>=60000 then window_start=redis_now;window_count=0 end
      if window_count+new_count>max_alloc then return {'CAPACITY_UNHEALTHY'} end
      local cap=tonumber(ARGV[4]);local interval=tonumber(ARGV[5]);local horizon=tonumber(ARGV[6])
      local proposed={}
      for i=4,5 do
        local tokens=tonumber(redis.call('HGET',KEYS[i],'tokens') or tostring(cap*1000000))
        local last=tonumber(redis.call('HGET',KEYS[i],'last_ms') or tostring(redis_now))
        local elapsed=math.max(0,redis_now-last)
        tokens=math.min(cap*1000000,tokens+math.floor(elapsed*1000000/interval))
        proposed[i]={tokens=tokens,last=math.max(last,redis_now)}
      end
      if proposed[4].tokens<1000000 then return {'QUOTA_EXCEEDED'} end
      for i=4,5 do
        local existed=redis.call('EXISTS',KEYS[i]);local tokens=proposed[i].tokens
        if i==4 or tokens>=1000000 then tokens=tokens-1000000 end
        redis.call('HSET',KEYS[i],'tokens',tokens,'last_ms',proposed[i].last,
          'last_used_ms',redis_now,'capacity',cap,'interval_ms',interval,
          'cleanup_horizon_ms',horizon)
        redis.call('ZADD',KEYS[3],redis_now,KEYS[i])
        if existed==0 then redis.call('INCR',KEYS[1]) end
      end
      redis.call('HSET',KEYS[2],'window_start',window_start,'count',window_count+new_count)
      return {'ALLOWED'}
      """;

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final OidcQuotaKeyEncoder keys;
  private final OidcClockSafetyGuard clockGuard;
  private final OidcHostTimeHealth hostTime;
  private final WebBffProperties.OidcQuota policy;
  private final MeterRegistry meters;

  public RedisOidcQuota(
      String redisUri,
      OidcQuotaKeyEncoder keys,
      OidcClockSafetyGuard clockGuard,
      OidcHostTimeHealth hostTime,
      WebBffProperties.OidcQuota policy,
      MeterRegistry meters) {
    RedisURI uri = RedisURI.create(redisUri);
    uri.setTimeout(BUDGET);
    this.client = RedisClient.create(uri);
    this.connection = client.connect();
    this.keys = keys;
    this.clockGuard = clockGuard;
    this.hostTime = hostTime;
    this.policy = policy;
    this.meters = meters;
  }

  @Override
  public void consume(Operation operation, byte[] clientAddress) {
    long started = System.nanoTime();
    try {
      long appNow = clockGuard.requireHealthy(hostTime.synchronizedHealthy());
      requireCapacity(started);
      OidcQuotaKeyEncoder.EncodedKeys encoded = keys.encode(operation, clientAddress);
      Bucket bucket = Bucket.forOperation(operation);
      Object raw =
          connection
              .sync()
              .eval(
                  SCRIPT,
                  ScriptOutputType.MULTI,
                  new String[] {
                    "web-bff:oidc-quota:v1:active-count",
                    "web-bff:oidc-quota:v1:allocation-window",
                    "web-bff:oidc-quota:v1:index",
                    encoded.exactIpKey(),
                    encoded.aggregateNetworkKey()
                  },
                  Long.toString(appNow),
                  Integer.toString(policy.maxActiveBuckets()),
                  Integer.toString(policy.maxNewBucketsPerMinute()),
                  Integer.toString(bucket.capacity()),
                  Long.toString(bucket.refillIntervalMs()),
                  Long.toString(bucket.cleanupHorizonMs()));
      if (elapsedMillis(started) > BUDGET.toMillis()) throw unavailable(null);
      String result = ((List<?>) raw).getFirst().toString();
      switch (result) {
        case "ALLOWED" -> record(operation, "allowed");
        case "QUOTA_EXCEEDED" ->
            throw new BffException(BffError.RATE_LIMITED, "OIDC request quota exceeded");
        case "TIME_UNHEALTHY" -> throw timeUnhealthy();
        case "CAPACITY_UNHEALTHY" -> throw capacityUnhealthy();
        default -> throw unavailable(null);
      }
    } catch (BffException exception) {
      record(operation, outcome(exception.error()));
      throw exception;
    } catch (RedisConnectionException | RedisCommandExecutionException exception) {
      record(operation, "unavailable");
      throw unavailable(exception);
    } catch (RuntimeException exception) {
      record(operation, "unavailable");
      throw unavailable(exception);
    }
  }

  private void record(Operation operation, String outcome) {
    try {
      meters
          .counter("web_bff_oidc_quota_total", "operation", operation.name(), "outcome", outcome)
          .increment();
    } catch (RuntimeException ignored) {
    }
  }

  private static String outcome(BffError error) {
    return switch (error) {
      case RATE_LIMITED -> "denied";
      case QUOTA_TIME_SOURCE_UNHEALTHY -> "time_unhealthy";
      case QUOTA_CAPACITY_UNHEALTHY -> "capacity_unhealthy";
      default -> "unavailable";
    };
  }

  private void requireCapacity(long started) {
    try {
      String info = connection.sync().info("memory");
      Map<String, String> eviction = connection.sync().configGet("maxmemory-policy");
      if (elapsedMillis(started) > BUDGET.toMillis()) throw unavailable(null);
      long used = infoValue(info, "used_memory");
      long maximum = infoValue(info, "maxmemory");
      if (!"noeviction".equals(eviction.get("maxmemory-policy"))
          || maximum <= 0
          || used < 0
          || used * 100L > maximum * (100L - policy.minimumMemoryHeadroomPercent())) {
        throw capacityUnhealthy();
      }
    } catch (BffException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw unavailable(exception);
    }
  }

  private static long infoValue(String info, String name) {
    for (String line : info.split("\r?\n")) {
      if (line.startsWith(name + ":")) {
        try {
          return Long.parseLong(line.substring(name.length() + 1).strip());
        } catch (NumberFormatException exception) {
          return -1;
        }
      }
    }
    return -1;
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

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static BffException timeUnhealthy() {
    return new BffException(
        BffError.QUOTA_TIME_SOURCE_UNHEALTHY, "OIDC quota time source is unavailable");
  }

  private static BffException capacityUnhealthy() {
    return new BffException(
        BffError.QUOTA_CAPACITY_UNHEALTHY, "OIDC quota capacity is unavailable");
  }

  private static BffException unavailable(Throwable cause) {
    return new BffException(BffError.OIDC_UNAVAILABLE, "OIDC quota is unavailable", cause);
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }

  private record Bucket(int capacity, long refillIntervalMs, long cleanupHorizonMs) {
    static Bucket forOperation(Operation operation) {
      return switch (operation) {
        case OIDC_START -> new Bucket(60, 5000, Duration.ofHours(1).toMillis());
        case OIDC_CALLBACK -> new Bucket(120, 500, Duration.ofMinutes(30).toMillis());
      };
    }
  }
}
