package com.sajtech.identity.infrastructure.security.password;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.port.out.PasswordVerificationPort;
import com.sajtech.identity.application.registration.RegistrationError;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import java.util.concurrent.Semaphore;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

public final class Argon2idPasswordHasher implements PasswordHashPort, PasswordVerificationPort {
  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19 * 1024;
  private static final int ITERATIONS = 2;
  private static final String DUMMY_PASSWORD = "HooshiX-local-auth-dummy-proof-v1";
  private final Argon2PasswordEncoder encoder =
      new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);
  private final Semaphore permits;
  private final String dummyHash;

  public Argon2idPasswordHasher(int maxConcurrentHashes) {
    if (maxConcurrentHashes <= 0) {
      throw new IllegalArgumentException("Argon2 concurrency must be positive");
    }
    permits = new Semaphore(maxConcurrentHashes);
    dummyHash = encoder.encode(DUMMY_PASSWORD);
  }

  @Override
  public String hash(String normalizedPassword) {
    if (!permits.tryAcquire()) {
      throw new RegistrationException(
          RegistrationError.DEPENDENCY_UNAVAILABLE, "Password hashing capacity is unavailable");
    }
    try {
      return encoder.encode(normalizedPassword);
    } finally {
      permits.release();
    }
  }

  @Override
  public boolean matches(String normalizedPassword, String encodedHash) {
    if (!permits.tryAcquire()) {
      throw new AuthenticationException(
          AuthenticationError.AUTHENTICATION_OVERLOADED,
          "Password verification capacity is unavailable");
    }
    try {
      return encoder.matches(normalizedPassword, encodedHash == null ? dummyHash : encodedHash);
    } catch (IllegalArgumentException exception) {
      throw new AuthenticationException(
          AuthenticationError.SESSION_STATE_INVALID, "Stored credential is invalid", exception);
    } finally {
      permits.release();
    }
  }
}
