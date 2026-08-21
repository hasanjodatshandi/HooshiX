package com.sajtech.authorization.infrastructure.quota;

import com.sajtech.authorization.application.*;
import com.sajtech.authorization.application.model.ActorContext;
import com.sajtech.authorization.application.port.out.AdminQuota;
import com.sajtech.authorization.infrastructure.security.keyring.*;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.*;
import javax.crypto.Mac;

public final class RedisAdminQuota implements AdminQuota, AutoCloseable {
  private static final Duration BUDGET = Duration.ofMillis(75);
  private static final int ACTOR_CAPACITY = 120, SCOPE_CAPACITY = 600;
  private static final long ACTOR_INTERVAL = 500, SCOPE_INTERVAL = 200, HORIZON = 3_600_000;
  static final String ACTIVE = "authorization:admin-quota:v1:active-count";
  static final String ALLOC = "authorization:admin-quota:v1:allocation-window";
  static final String INDEX = "authorization:admin-quota:v1:index";
  private static final String SCRIPT =
"""
local app=tonumber(ARGV[1]); local maxa=tonumber(ARGV[2]); local maxn=tonumber(ARGV[3]); local cost=tonumber(ARGV[4])
local ac=tonumber(ARGV[5]); local acap=tonumber(ARGV[6]); local ai=tonumber(ARGV[7]); local ah=tonumber(ARGV[8])
local sc=tonumber(ARGV[9]); local scap=tonumber(ARGV[10]); local si=tonumber(ARGV[11]); local sh=tonumber(ARGV[12])
if cost<1 or cost>100 or ac<1 or sc<1 or maxa<1 or maxn<1 then return {'CAPACITY_UNHEALTHY'} end
local t=redis.call('TIME'); local now=tonumber(t[1])*1000+math.floor(tonumber(t[2])/1000)
if math.abs(now-app)>2000 then return {'TIME_UNHEALTHY'} end
local function inspect(start,count,cap,interval)
 local existing=0; local tokens=cap*1000000; local last=now
 for i=0,count-1 do
  local k=KEYS[start+i]
  if redis.call('EXISTS',k)==1 then
   existing=existing+1
   local st=tonumber(redis.call('HGET',k,'tokens') or tostring(cap*1000000)); local sl=tonumber(redis.call('HGET',k,'last_ms') or tostring(now))
   local refill=math.floor(math.max(0,now-sl)*1000000/interval); tokens=math.min(tokens,math.min(cap*1000000,st+refill)); last=math.max(last,sl)
  end
 end
 return {existing=existing,tokens=tokens,last=last}
end
local function consolidate(start,count,state,cap,interval,horizon,debit)
 if state.existing==0 and debit==0 then return end
 local activekey=KEYS[start]
 for i=1,count-1 do local old=KEYS[start+i]; if old~=activekey and redis.call('EXISTS',old)==1 then redis.call('DEL',old);redis.call('ZREM',KEYS[3],old) end end
 local charge=0; if debit==1 then charge=cost*1000000 end
 redis.call('HSET',activekey,'tokens',state.tokens-charge,'last_ms',math.max(state.last,now),'last_used_ms',now,'capacity',cap,'interval_ms',interval,'cleanup_horizon_ms',horizon)
 redis.call('ZADD',KEYS[3],now,activekey)
end
local ast=4; local sst=ast+ac; local a=inspect(ast,ac,acap,ai); local s=inspect(sst,sc,scap,si)
local active=tonumber(redis.call('GET',KEYS[1]) or '0')
if a.tokens<cost*1000000 or s.tokens<cost*1000000 then
 local after=active
 if a.existing>0 then after=after+1-a.existing end
 if s.existing>0 then after=after+1-s.existing end
 if after<0 then return {'CAPACITY_UNHEALTHY'} end
 consolidate(ast,ac,a,acap,ai,ah,0); consolidate(sst,sc,s,scap,si,sh,0); redis.call('SET',KEYS[1],after)
 return {'QUOTA_EXCEEDED'}
end
local logical=0; if a.existing==0 then logical=logical+1 end; if s.existing==0 then logical=logical+1 end
local after=active+(1-a.existing)+(1-s.existing); if after<0 or after>maxa then return {'CAPACITY_UNHEALTHY'} end
local ws=tonumber(redis.call('HGET',KEYS[2],'window_start') or tostring(now)); local wc=tonumber(redis.call('HGET',KEYS[2],'count') or '0')
if now-ws>=60000 then ws=now; wc=0 end; if wc+logical>maxn then return {'CAPACITY_UNHEALTHY'} end
consolidate(ast,ac,a,acap,ai,ah,1); consolidate(sst,sc,s,scap,si,sh,1)
redis.call('SET',KEYS[1],after); redis.call('HSET',KEYS[2],'window_start',ws,'count',wc+logical)
return {'ALLOWED',tostring(after),tostring(logical)}
""";

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final FileBackedKeyRing keys;
  private final ClockSafetyGuard guard;
  private final HostTimeHealth host;
  private final int maxActive, maxNew, headroom;

  public RedisAdminQuota(
      String uri,
      FileBackedKeyRing keys,
      ClockSafetyGuard guard,
      HostTimeHealth host,
      int maxActive,
      int maxNew,
      int headroom) {
    if (maxActive < 1 || maxNew < 1 || headroom < 30 || headroom >= 100) throw unavailable(null);
    RedisURI redis = RedisURI.create(uri);
    redis.setTimeout(BUDGET);
    client = RedisClient.create(redis);
    connection = client.connect();
    this.keys = Objects.requireNonNull(keys);
    this.guard = Objects.requireNonNull(guard);
    this.host = Objects.requireNonNull(host);
    this.maxActive = maxActive;
    this.maxNew = maxNew;
    this.headroom = headroom;
  }

  @Override
  public void acquire(ActorContext actor, int cost) {
    if (actor == null
        || actor.userId() == null
        || actor.tenantId() == null
        || cost < 1
        || cost > 100)
      throw new AuthorizationException(
          AuthorizationError.LIMIT_EXCEEDED, "Authorization mutation cost is invalid");
    long started = System.nanoTime(), now = guard.requireHealthy(host.synchronizedHealthy());
    memory(started);
    List<String> actorKeys =
        bucketKeys("actor", actor.userId().toString(), actor.tenantId().toString());
    List<String> scopeKeys = bucketKeys("tenant", actor.tenantId().toString());
    List<String> redisKeys = new ArrayList<>(3 + actorKeys.size() + scopeKeys.size());
    redisKeys.add(ACTIVE);
    redisKeys.add(ALLOC);
    redisKeys.add(INDEX);
    redisKeys.addAll(actorKeys);
    redisKeys.addAll(scopeKeys);
    String[] argv = {
      Long.toString(now),
      Integer.toString(maxActive),
      Integer.toString(maxNew),
      Integer.toString(cost),
      Integer.toString(actorKeys.size()),
      Integer.toString(ACTOR_CAPACITY),
      Long.toString(ACTOR_INTERVAL),
      Long.toString(HORIZON),
      Integer.toString(scopeKeys.size()),
      Integer.toString(SCOPE_CAPACITY),
      Long.toString(SCOPE_INTERVAL),
      Long.toString(HORIZON)
    };
    try {
      Object result =
          connection
              .sync()
              .eval(SCRIPT, ScriptOutputType.MULTI, redisKeys.toArray(String[]::new), argv);
      if (elapsed(started) > BUDGET.toMillis()) throw unavailable(null);
      String code = ((List<?>) result).getFirst().toString();
      switch (code) {
        case "ALLOWED" -> {
          return;
        }
        case "QUOTA_EXCEEDED" ->
            throw new AuthorizationException(
                AuthorizationError.QUOTA_EXCEEDED, "Authorization admin quota exceeded");
        case "TIME_UNHEALTHY", "CAPACITY_UNHEALTHY" ->
            throw new AuthorizationException(
                AuthorizationError.QUOTA_UNAVAILABLE, "Authorization quota unavailable");
        default -> throw unavailable(null);
      }
    } catch (AuthorizationException e) {
      throw e;
    } catch (RedisConnectionException | RedisCommandExecutionException e) {
      throw unavailable(e);
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  List<String> bucketKeys(String dimension, String... parts) {
    KeyRingMaterial active = keys.activeKey();
    List<KeyRingMaterial> all = keys.allKeys();
    List<String> out = new ArrayList<>(all.size());
    out.add(key(active, dimension, parts));
    for (KeyRingMaterial material : all)
      if (!material.keyId().equals(active.keyId())) out.add(key(material, dimension, parts));
    return List.copyOf(out);
  }

  private static String key(KeyRingMaterial material, String dimension, String... parts) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(material.key());
      put(mac, "hooshix:authorization:admin-quota:v1");
      put(mac, dimension);
      for (String part : parts) put(mac, part);
      return "authorization:admin-quota:v1:"
          + material.keyId()
          + ":"
          + HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException e) {
      throw unavailable(e);
    }
  }

  private static void put(Mac mac, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    mac.update(bytes);
  }

  private void memory(long started) {
    try {
      String info = connection.sync().info("memory");
      if (elapsed(started) > BUDGET.toMillis()) throw unavailable(null);
      long used = value(info, "used_memory"), max = value(info, "maxmemory");
      if (max <= 0 || used < 0 || used * 100L > max * (100L - headroom)) throw unavailable(null);
    } catch (AuthorizationException e) {
      throw e;
    } catch (RuntimeException e) {
      throw unavailable(e);
    }
  }

  private static long value(String info, String name) {
    for (String line : info.split("\\r?\\n"))
      if (line.startsWith(name + ":"))
        try {
          return Long.parseLong(line.substring(name.length() + 1).trim());
        } catch (NumberFormatException e) {
          return -1;
        }
    return -1;
  }

  private static long elapsed(long started) {
    return (System.nanoTime() - started) / 1_000_000L;
  }

  private static AuthorizationException unavailable(Throwable cause) {
    return cause == null
        ? new AuthorizationException(
            AuthorizationError.QUOTA_UNAVAILABLE, "Authorization quota unavailable")
        : new AuthorizationException(
            AuthorizationError.QUOTA_UNAVAILABLE, "Authorization quota unavailable", cause);
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
