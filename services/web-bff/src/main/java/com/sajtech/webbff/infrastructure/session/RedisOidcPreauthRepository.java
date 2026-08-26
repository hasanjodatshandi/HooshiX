package com.sajtech.webbff.infrastructure.session;

import com.sajtech.webbff.application.*;
import com.sajtech.webbff.application.model.*;
import com.sajtech.webbff.application.port.out.OidcPreauthPort;
import com.sajtech.webbff.infrastructure.security.SessionCrypto;
import com.sajtech.webbff.infrastructure.security.SessionCrypto.EncryptedValue;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

public final class RedisOidcPreauthRepository implements OidcPreauthPort, AutoCloseable {
  private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(75);
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration TTL = Duration.ofMinutes(10);
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
  private static final String BEGIN =
      """
      local fields=redis.call('HKEYS',KEYS[1]);local live=0;local now=tonumber(ARGV[1]);
      for _,field in ipairs(fields) do
        if string.sub(field,1,4)=='exp:' then
          local expiry=tonumber(redis.call('HGET',KEYS[1],field) or '0');local suffix=string.sub(field,5);
          if expiry<=now then redis.call('HDEL',KEYS[1],field,'tx:'..suffix) else live=live+1 end
        end
      end
      if live>=5 then return 0 end
      redis.call('HSET',KEYS[1],'tx:'..ARGV[2],ARGV[3],'exp:'..ARGV[2],ARGV[4]);
      redis.call('PEXPIREAT',KEYS[1],tonumber(ARGV[5]));return 1
      """;
  private static final String CONSUME =
      """
      local now=tonumber(ARGV[1]);
      for i=2,#ARGV do
        local field='tx:'..ARGV[i];local expiryField='exp:'..ARGV[i];
        local value=redis.call('HGET',KEYS[1],field);
        if value then
          local expiry=tonumber(redis.call('HGET',KEYS[1],expiryField) or '0');
          redis.call('HDEL',KEYS[1],field,expiryField);
          if expiry>now then return value else return nil end
        end
      end
      return nil
      """;

  private final RedisClient client;
  private final StatefulRedisConnection<String, String> connection;
  private final SessionCrypto crypto;
  private final Clock clock;

  public RedisOidcPreauthRepository(String redisUri, SessionCrypto crypto, Clock clock) {
    RedisURI redis = RedisURI.create(redisUri);
    redis.setTimeout(CONNECT_TIMEOUT);
    client = RedisClient.create(redis);
    connection = client.connect();
    connection.setTimeout(COMMAND_TIMEOUT);
    this.crypto = crypto;
    this.clock = clock;
  }

  @Override
  public OidcAuthorizationStart begin(
      String existingCookie,
      OidcPurpose purpose,
      String browserSessionLocator,
      String redirectUri,
      String returnTarget) {
    SessionCrypto.IssuedOpaque opaque = container(existingCookie);
    SessionCrypto.IssuedState state = crypto.issueOidcState();
    String nonce = random(), verifier = random();
    String challenge = B64.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    Instant now = clock.instant(), expires = now.plus(TTL);
    OidcPreauthTransaction transaction =
        new OidcPreauthTransaction(
            purpose,
            browserSessionLocator,
            nonce,
            verifier,
            redirectUri,
            returnTarget,
            now,
            expires);
    EncryptedValue encrypted = crypto.encryptOidcPreauth(opaque.locator(), encode(transaction));
    String packed = encrypted.keyId() + "|" + encrypted.nonce() + "|" + encrypted.ciphertext();
    final Long accepted;
    try {
      accepted =
          connection
              .sync()
              .eval(
                  BEGIN,
                  ScriptOutputType.INTEGER,
                  new String[] {opaque.locator()},
                  Long.toString(clock.millis()),
                  state.locator(),
                  packed,
                  Long.toString(expires.toEpochMilli()),
                  Long.toString(expires.toEpochMilli()));
    } catch (RedisException exception) {
      throw unavailable(exception);
    }
    if (accepted == null || accepted != 1L) {
      throw new BffException(BffError.RATE_LIMITED, "OIDC pre-auth limit reached");
    }
    return new OidcAuthorizationStart(
        opaque.cookieValue(), state.clear(), nonce, verifier, challenge, expires);
  }

  @Override
  public Optional<OidcPreauthTransaction> consume(String cookie, String state) {
    final String locator;
    try {
      locator = crypto.preauthLocatorFromCookie(cookie);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
    List<String> candidates = crypto.oidcStateLocators(state);
    if (candidates.isEmpty()) return Optional.empty();
    String[] args = new String[candidates.size() + 1];
    args[0] = Long.toString(clock.millis());
    for (int index = 0; index < candidates.size(); index++) args[index + 1] = candidates.get(index);
    final String packed;
    try {
      packed =
          connection.sync().eval(CONSUME, ScriptOutputType.VALUE, new String[] {locator}, args);
    } catch (RedisException exception) {
      throw unavailable(exception);
    }
    if (packed == null) return Optional.empty();
    String[] parts = packed.split("[|]", 3);
    if (parts.length != 3) return Optional.empty();
    try {
      String clear =
          crypto.decryptOidcPreauth(locator, new EncryptedValue(parts[0], parts[1], parts[2]));
      OidcPreauthTransaction transaction = decode(clear);
      if (!clock.instant().isBefore(transaction.expiresAt())) return Optional.empty();
      return Optional.of(transaction);
    } catch (RuntimeException exception) {
      return Optional.empty();
    }
  }

  private SessionCrypto.IssuedOpaque container(String existingCookie) {
    if (existingCookie != null) {
      try {
        String locator = crypto.preauthLocatorFromCookie(existingCookie);
        return new SessionCrypto.IssuedOpaque(existingCookie, locator);
      } catch (RuntimeException ignored) {
      }
    }
    return crypto.issuePreauthToken();
  }

  private static String encode(OidcPreauthTransaction value) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(bytes);
      out.writeInt(1);
      write(out, value.purpose().name());
      write(out, value.browserSessionLocator());
      write(out, value.nonce());
      write(out, value.verifier());
      write(out, value.redirectUri());
      write(out, value.returnTarget());
      out.writeLong(value.createdAt().getEpochSecond());
      out.writeInt(value.createdAt().getNano());
      out.writeLong(value.expiresAt().getEpochSecond());
      out.writeInt(value.expiresAt().getNano());
      out.flush();
      return B64.encodeToString(bytes.toByteArray());
    } catch (IOException impossible) {
      throw new IllegalStateException("OIDC pre-auth encoding failed", impossible);
    }
  }

  private static OidcPreauthTransaction decode(String value) {
    try {
      byte[] bytes = Base64.getUrlDecoder().decode(value);
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      if (in.readInt() != 1) throw new IOException("version");
      OidcPreauthTransaction result =
          new OidcPreauthTransaction(
              OidcPurpose.valueOf(read(in)),
              read(in),
              read(in),
              read(in),
              read(in),
              read(in),
              Instant.ofEpochSecond(in.readLong(), in.readInt()),
              Instant.ofEpochSecond(in.readLong(), in.readInt()));
      if (in.read() != -1) throw new IOException("trailing");
      return result;
    } catch (IOException | IllegalArgumentException exception) {
      throw new IllegalStateException("OIDC pre-auth payload is invalid", exception);
    }
  }

  private static void write(DataOutputStream out, String value) throws IOException {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);
    out.write(bytes);
  }

  private static String read(DataInputStream in) throws IOException {
    int size = in.readInt();
    if (size < 1 || size > 2048) throw new IOException("size");
    byte[] bytes = in.readNBytes(size);
    if (bytes.length != size) throw new IOException("truncated");
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String random() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    try {
      return B64.encodeToString(bytes);
    } finally {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  StatefulRedisConnection<String, String> connection() {
    return connection;
  }

  private static BffException unavailable(RedisException cause) {
    return new BffException(BffError.OIDC_UNAVAILABLE, "OIDC pre-auth store is unavailable", cause);
  }

  @Override
  public void close() {
    connection.close();
    client.shutdown();
  }
}
