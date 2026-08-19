package com.sajtech.identity.infrastructure.quota;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Clock;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public final class RedisQuotaCleanupWorker implements SmartLifecycle {
  private static final Logger LOGGER = LoggerFactory.getLogger(RedisQuotaCleanupWorker.class);
  private static final int BATCH = 64;
  private static final String SCRIPT =
"""
local now=tonumber(ARGV[1])
local batch=tonumber(ARGV[2])
local members=redis.call('ZRANGE',KEYS[1],0,batch-1)
local removed=0
for _,key in ipairs(members) do
  if redis.call('EXISTS',key)==0 then
    redis.call('ZREM',KEYS[1],key)
    local active=tonumber(redis.call('GET',KEYS[2]) or '0')
    if active>0 then redis.call('DECR',KEYS[2]) end
    removed=removed+1
  else
    local last=tonumber(redis.call('HGET',key,'last_used_ms') or '0')
    local horizon=tonumber(redis.call('HGET',key,'cleanup_horizon_ms') or '0')
    local cap=tonumber(redis.call('HGET',key,'capacity') or '0')
    local interval=tonumber(redis.call('HGET',key,'interval_ms') or '0')
    local tokens=tonumber(redis.call('HGET',key,'tokens') or '0')
    local last_ms=tonumber(redis.call('HGET',key,'last_ms') or tostring(now))
    if last>0 and horizon>0 and cap>0 and interval>0 and now-last>=horizon then
      local effective=math.max(now,last_ms)
      local refilled=math.min(cap*1000000,tokens+math.floor((effective-last_ms)*1000000/interval))
      if refilled>=cap*1000000 then
        redis.call('DEL',key)
        redis.call('ZREM',KEYS[1],key)
        local active=tonumber(redis.call('GET',KEYS[2]) or '0')
        if active>0 then redis.call('DECR',KEYS[2]) end
        removed=removed+1
      else
        redis.call('ZADD',KEYS[1],last,key)
      end
    else
      break
    end
  end
end
return removed
""";
  private final StatefulRedisConnection<String, String> connection;
  private final ClockSafetyGuard guard;
  private final HostTimeHealth hostTime;
  private final Clock clock;
  private final ScheduledExecutorService executor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("identity-quota-cleanup").factory());
  private volatile boolean running;

  public RedisQuotaCleanupWorker(
      RedisSemanticQuota quota, ClockSafetyGuard guard, HostTimeHealth hostTime, Clock clock) {
    this.connection = quota.connection();
    this.guard = guard;
    this.hostTime = hostTime;
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
    if (!running) return;
    executor.schedule(this::cycle, 30, TimeUnit.SECONDS);
  }

  private void cycle() {
    try {
      if (guard.isHealthy(hostTime.synchronizedHealthy()))
        connection
            .sync()
            .eval(
                SCRIPT,
                ScriptOutputType.INTEGER,
                new String[] {"identity:quota:v1:index", "identity:quota:v1:active-count"},
                Long.toString(clock.millis()),
                Integer.toString(BATCH));
    } catch (RuntimeException exception) {
      LOGGER
          .atWarn()
          .addKeyValue("eventCode", "IDENTITY_QUOTA_CLEANUP_FAILED")
          .log("Identity quota cleanup cycle failed");
    } finally {
      schedule();
    }
  }
}
