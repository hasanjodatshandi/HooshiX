package com.sajtech.identity.infrastructure.passwordscreening;

import com.sajtech.compromisedpassword.contract.v1.CompromisedHashMatch;
import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.port.out.PasswordScreeningPort;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class GrpcCompromisedPasswordAdapter implements PasswordScreeningPort {
  private static final Pattern SUFFIX = Pattern.compile("^[0-9A-F]{35}$");
  private static final int MAX_MATCHES = 100_000;
  private static final int MAX_SERIALIZED_RESPONSE_BYTES = 2 * 1024 * 1024;

  private final CompromisedPasswordServiceGrpc.CompromisedPasswordServiceBlockingStub stub;
  private final Semaphore bulkhead;

  public GrpcCompromisedPasswordAdapter(ManagedChannel channel, int maximumConcurrentLookups) {
    this.stub = CompromisedPasswordServiceGrpc.newBlockingStub(channel);
    if (maximumConcurrentLookups < 1) {
      throw new IllegalArgumentException("maximumConcurrentLookups must be positive");
    }
    this.bulkhead = new Semaphore(maximumConcurrentLookups, true);
  }

  @Override
  public void requireNotCompromised(String normalizedPassword) {
    if (!bulkhead.tryAcquire()) {
      throw new RegistrationException(RegistrationError.PASSWORD_SCREENING_UNAVAILABLE);
    }
    byte[] digest = sha1(normalizedPassword);
    try {
      String prefix = hex(digest, 0, 5);
      String localSuffix = hexSuffix(digest);
      LookupPrefixResponse response;
      try {
        response =
            stub.withDeadlineAfter(900, TimeUnit.MILLISECONDS)
                .lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix(prefix).build());
      } catch (StatusRuntimeException exception) {
        throw new RegistrationException(RegistrationError.PASSWORD_SCREENING_UNAVAILABLE, exception);
      }
      validateResponse(response);
      for (CompromisedHashMatch match : response.getMatchesList()) {
        if (MessageDigest.isEqual(
            localSuffix.getBytes(StandardCharsets.US_ASCII),
            match.getSuffix().getBytes(StandardCharsets.US_ASCII))) {
          throw new RegistrationException(RegistrationError.PASSWORD_COMPROMISED);
        }
      }
    } finally {
      Arrays.fill(digest, (byte) 0);
      bulkhead.release();
    }
  }

  private static void validateResponse(LookupPrefixResponse response) {
    if (response.getSerializedSize() > MAX_SERIALIZED_RESPONSE_BYTES
        || response.getMatchesCount() > MAX_MATCHES) {
      throw new RegistrationException(RegistrationError.PASSWORD_SCREENING_UNAVAILABLE);
    }
    Set<String> unique = new HashSet<>();
    for (CompromisedHashMatch match : response.getMatchesList()) {
      String suffix = match.getSuffix();
      if (!SUFFIX.matcher(suffix).matches()
          || match.getOccurrenceCount() <= 0
          || !unique.add(suffix)) {
        throw new RegistrationException(RegistrationError.PASSWORD_SCREENING_UNAVAILABLE);
      }
    }
  }

  private static byte[] sha1(String value) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-1 is unavailable", exception);
    }
  }

  private static String hex(byte[] digest, int nibbleStart, int nibbleLength) {
    String full = java.util.HexFormat.of().withUpperCase().formatHex(digest);
    return full.substring(nibbleStart, nibbleStart + nibbleLength);
  }

  private static String hexSuffix(byte[] digest) {
    String full = java.util.HexFormat.of().withUpperCase().formatHex(digest);
    return full.substring(5).toUpperCase(Locale.ROOT);
  }
}
