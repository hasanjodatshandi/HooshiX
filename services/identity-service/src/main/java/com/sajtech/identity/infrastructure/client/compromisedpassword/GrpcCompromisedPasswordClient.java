package com.sajtech.identity.infrastructure.client.compromisedpassword;

import com.sajtech.compromisedpassword.contract.v1.CompromisedPasswordServiceGrpc;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixRequest;
import com.sajtech.compromisedpassword.contract.v1.LookupPrefixResponse;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.port.out.CompromisedPasswordPort;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class GrpcCompromisedPasswordClient implements CompromisedPasswordPort {
  private static final long DEADLINE_MS = 900;
  private static final Pattern SUFFIX = Pattern.compile("^[0-9A-F]{35}$");
  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  private final ManagedChannel channel;
  private final Semaphore inFlight;

  public GrpcCompromisedPasswordClient(ManagedChannel channel, int maxInFlight) {
    if (maxInFlight <= 0)
      throw new IllegalArgumentException("Compromised Password concurrency must be positive");
    this.channel = channel;
    this.inFlight = new Semaphore(maxInFlight);
  }

  @Override
  public void requireNotCompromised(String normalizedPassword) {
    if (!inFlight.tryAcquire()) throw unavailable(null);
    byte[] digest = sha1(normalizedPassword);
    try {
      String full = HEX.formatHex(digest);
      String prefix = full.substring(0, 5);
      LookupPrefixResponse response;
      try {
        response =
            CompromisedPasswordServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS)
                .lookupPrefix(LookupPrefixRequest.newBuilder().setPrefix(prefix).build());
      } catch (StatusRuntimeException exception) {
        throw unavailable(exception);
      }
      for (var match : response.getMatchesList()) {
        String suffix = match.getSuffix();
        if (!SUFFIX.matcher(suffix).matches() || match.getOccurrenceCount() == 0)
          throw unavailable(null);
        byte[] candidate;
        try {
          candidate = HEX.parseHex(prefix + suffix);
        } catch (IllegalArgumentException exception) {
          throw unavailable(exception);
        }
        try {
          if (MessageDigest.isEqual(digest, candidate))
            throw new RegistrationException(
                RegistrationError.COMPROMISED_PASSWORD, "Password is not acceptable");
        } finally {
          java.util.Arrays.fill(candidate, (byte) 0);
        }
      }
    } finally {
      java.util.Arrays.fill(digest, (byte) 0);
      inFlight.release();
    }
  }

  private static byte[] sha1(String value) {
    try {
      return MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-1 screening digest is unavailable", impossible);
    }
  }

  private static RegistrationException unavailable(Throwable cause) {
    return cause == null
        ? new RegistrationException(
            RegistrationError.DEPENDENCY_UNAVAILABLE,
            "Compromised Password dependency is unavailable")
        : new RegistrationException(
            RegistrationError.DEPENDENCY_UNAVAILABLE,
            "Compromised Password dependency is unavailable",
            cause);
  }
}
