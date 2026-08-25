package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.authentication.model.GeneratedRefreshCredential;
import com.sajtech.identity.application.authentication.model.LocalCredentialRecord;
import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.PasswordVerificationPort;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.mfa.port.out.MfaAuthenticationGate;
import com.sajtech.identity.application.password.PasswordError;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.model.PasswordPolicy;
import com.sajtech.identity.application.password.port.in.ChangePassword;
import com.sajtech.identity.application.password.port.in.ChangePasswordCommand;
import com.sajtech.identity.application.password.port.in.PasswordChangeSession;
import com.sajtech.identity.application.registration.port.out.CompromisedPasswordPort;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class ChangePasswordUseCase implements ChangePassword {
  private static final Duration RECENT_AUTH = Duration.ofMinutes(5);
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private final AuthenticationStore store;
  private final RefreshCredentialLookup lookup;
  private final SessionCredentialPort credentials;
  private final PasswordVerificationPort verifier;
  private final PasswordHashPort hashes;
  private final CompromisedPasswordPort compromised;
  private final PasswordNormalizer normalizer;
  private final TransactionRunner transactions;
  private final Clock clock;
  private final MfaAuthenticationGate mfa;

  public ChangePasswordUseCase(
      AuthenticationStore store,
      SessionCredentialPort credentials,
      PasswordVerificationPort verifier,
      PasswordHashPort hashes,
      CompromisedPasswordPort compromised,
      PasswordNormalizer normalizer,
      TransactionRunner transactions,
      MfaAuthenticationGate mfa,
      Clock clock) {
    this.store = store;
    this.lookup = new RefreshCredentialLookup(credentials);
    this.credentials = credentials;
    this.verifier = verifier;
    this.hashes = hashes;
    this.compromised = compromised;
    this.normalizer = normalizer;
    this.transactions = transactions;
    this.mfa = mfa;
    this.clock = clock;
  }

  public ChangePasswordUseCase(
      AuthenticationStore store,
      SessionCredentialPort credentials,
      PasswordVerificationPort verifier,
      PasswordHashPort hashes,
      CompromisedPasswordPort compromised,
      PasswordNormalizer normalizer,
      TransactionRunner transactions,
      Clock clock) {
    this(
        store,
        credentials,
        verifier,
        hashes,
        compromised,
        normalizer,
        transactions,
        DisabledMfaGate.INSTANCE,
        clock);
  }

  @Override
  public PasswordChangeSession change(ChangePasswordCommand command) {
    if (command == null
        || command.requestId() == null
        || command.refreshCredential() == null
        || command.currentPassword() == null
        || command.newPassword() == null) throw invalid();
    Instant now = clock.instant();
    LockedRefreshCredential observed =
        lookup
            .find(store, command.refreshCredential())
            .orElseThrow(ChangePasswordUseCase::invalidSession);
    requireUsableRecentSession(observed, now, mfa.requiresMfa(observed.userId()));
    LocalCredentialRecord observedCredential =
        store
            .findLocalCredential(observed.userId())
            .orElseThrow(ChangePasswordUseCase::invalidCredentials);
    String current = normalize(command.currentPassword());
    if (!verifier.matches(current, observedCredential.passwordHash())) throw invalidCredentials();

    String next = normalize(command.newPassword());
    try {
      PasswordPolicy.validate(next);
    } catch (IllegalArgumentException exception) {
      throw invalid();
    }
    compromised.requireNotCompromised(next);
    String nextHash = hashes.hash(next);
    GeneratedRefreshCredential rotated = credentials.newRefreshCredential();

    return transactions.required(
        () -> {
          LockedRefreshCredential locked =
              lookup
                  .lock(store, command.refreshCredential())
                  .orElseThrow(ChangePasswordUseCase::invalidSession);
          requireSameSession(observed, locked);
          requireUsableRecentSession(locked, now, mfa.requiresMfa(locked.userId()));
          LocalCredentialRecord currentCredential =
              store
                  .lockLocalCredential(locked.userId())
                  .orElseThrow(ChangePasswordUseCase::invalidCredentials);
          if (!"ACTIVE".equals(currentCredential.userStatus())
              || !sameHash(observedCredential.passwordHash(), currentCredential.passwordHash())) {
            throw invalidCredentials();
          }
          Instant nextIdle = min(now.plus(IDLE_LIFETIME), locked.absoluteExpiresAt());
          if (!now.isBefore(nextIdle)) throw invalidSession();
          store.updatePasswordHash(locked.userId(), nextHash, now);
          store.rotateRefresh(locked, java.util.UUID.randomUUID(), rotated.digest(), now, nextIdle);
          store.revokeOtherFamilies(
              locked.userId(),
              locked.refreshFamilyId(),
              RefreshFamilyRevocationReason.PASSWORD_CHANGED,
              now);
          return new PasswordChangeSession(rotated.encoded(), nextIdle, locked.absoluteExpiresAt());
        });
  }

  private String normalize(String value) {
    try {
      return normalizer.normalize(value);
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static void requireUsableRecentSession(
      LockedRefreshCredential session, Instant now, boolean requireMfaAssurance) {
    if (!"ACTIVE".equals(session.credentialState())
        || !"ACTIVE".equals(session.familyState())
        || !"ACTIVE".equals(session.userStatus())
        || !now.isBefore(session.idleExpiresAt())
        || !now.isBefore(session.absoluteExpiresAt())) throw invalidSession();
    if (session.authenticatedAt().plus(RECENT_AUTH).isBefore(now)) {
      throw new PasswordException(
          PasswordError.RECENT_AUTHENTICATION_REQUIRED, "Recent authentication is required");
    }
    if (requireMfaAssurance
        && (session.mfaAuthenticatedAt() == null
            || session.mfaAuthenticatedAt().plus(RECENT_AUTH).isBefore(now))) {
      throw new PasswordException(
          PasswordError.RECENT_AUTHENTICATION_REQUIRED, "Recent MFA authentication is required");
    }
  }

  private static void requireSameSession(
      LockedRefreshCredential observed, LockedRefreshCredential locked) {
    if (!observed.userId().equals(locked.userId())
        || !observed.refreshFamilyId().equals(locked.refreshFamilyId())
        || !observed.credentialId().equals(locked.credentialId())) throw invalidSession();
  }

  private static boolean sameHash(String expected, String actual) {
    return expected != null
        && actual != null
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static PasswordException invalid() {
    return new PasswordException(PasswordError.INVALID_ARGUMENT, "Password request is invalid");
  }

  private static PasswordException invalidCredentials() {
    return new PasswordException(
        PasswordError.INVALID_CREDENTIALS, "Password credentials are invalid");
  }

  private static PasswordException invalidSession() {
    return new PasswordException(PasswordError.INVALID_SESSION, "Password session is invalid");
  }

  private enum DisabledMfaGate implements MfaAuthenticationGate {
    INSTANCE;

    @Override
    public boolean requiresMfa(java.util.UUID userId) {
      return false;
    }

    @Override
    public void replaceLoginChallenge(
        java.util.UUID challengeId,
        java.util.UUID userId,
        com.sajtech.identity.application.mfa.model.GeneratedMfaChallenge challenge,
        Instant now,
        Instant expiresAt) {
      throw new UnsupportedOperationException("MFA is disabled");
    }
  }
}
