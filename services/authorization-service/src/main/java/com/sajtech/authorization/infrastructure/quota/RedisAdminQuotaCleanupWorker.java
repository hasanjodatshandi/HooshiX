package com.sajtech.authorization.infrastructure.quota;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.context.SmartLifecycle;

public final class RedisAdminQuotaCleanupWorker implements SmartLifecycle {
  private static final Logger LOG = LoggerFactory.getLogger(RedisAdminQuotaCleanupWorker.class);
  private static final int BATCH = 64;
  private static final String SCRIPT =
"""
local now=tonumber(ARGV[1]);local batch=tonumber(ARGV[2]);local members=redis.call('ZRANGE',KEYS[1],0,batch-1);local removed=0
for _,key in ipairs(members) do if redis.call('EXISTS',key)==0 then redis.call('ZREM',KEYS[1],key);local a=tonumber(redis.call('GET',KEYS[2]) or '0');if a>0 then redis.call('DECR',KEYS[2]) end;removed=removed+1 else local last=tonumber(redis.call('HGET',key,'last_used_ms') or '0');local h=tonumber(redis.call('HGET',key,'cleanup_horizon_ms') or '0');local cap=tonumber(redis.call('HGET',key,'capacity') or '0');local int=tonumber(redis.call('HGET',key,'interval_ms') or '0');local tok=tonumber(redis.call('HGET',key,'tokens') or '0');local lm=tonumber(redis.call('HGET',key,'last_ms') or tostring(now));if last>0 and h>0 and cap>0 and int>0 and now-last>=h then local eff=math.max(now,lm);local ref=math.min(cap*1000000,tok+math.floor((eff-lm)*1000000/int));if ref>=cap*1000000 then redis.call('DEL',key);redis.call('ZREM',KEYS[1],key);local a=tonumber(redis.call('GET',KEYS[2]) or '0');if a>0 then redis.call('DECR',KEYS[2]) end;removed=removed+1 else redis.call('ZADD',KEYS[1],last,key) end else break end end end return removed
""";
  private final StatefulRedisConnection<String, String> connection;
  private final ClockSafetyGuard guard;
  private final HostTimeHealth host;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("authorization-admin-quota-cleanup").factory());
  private volatile boolean running;

  public RedisAdminQuotaCleanupWorker(
      RedisAdminQuota quota, ClockSafetyGuard guard, HostTimeHealth host, Clock clock) {
    this.connection = quota.connection();
    this.guard = guard;
    this.host = host;
    this.clock = clock;
  }

  @Override
  public synchronized void start() {
    if (running) return;
    running = true;
    schedule();
  }

  @Override
  public synchronized void stop() {
    running = false;
    executor.shutdownNow();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  private void schedule() {
    if (running) executor.schedule(this::cycle, 30, TimeUnit.SECONDS);
  }

  private void cycle() {
    try {
      if (guard.isHealthy(host.synchronizedHealthy()))
        connection
            .sync()
            .eval(
                SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {RedisAdminQuota.INDEX, RedisAdminQuota.ACTIVE},
                Long.toString(clock.millis()),
                Integer.toString(BATCH));
    } catch (RuntimeException e) {
      LOG.atWarn()
          .addKeyValue("eventCode", "AUTHORIZATION_ADMIN_QUOTA_CLEANUP_FAILED")
          .log("Authorization admin quota cleanup failed");
    } finally {
      schedule();
    }
  }
}
