package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.password.model.PasswordPolicy;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;

public final class ChangePasswordUseCase implements ChangePassword {
  private final AuthenticationStore store;
  private final RefreshCredentialLookup lookup;
  private final SessionCredentialPort credentials;
  private final PasswordVerificationPort verifier;
  private final PasswordHashPort hashes;
  private final CompromisedPasswordPort compromised;
  private final PasswordNormalizer normalizer;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ChangePasswordUseCase(AuthenticationStore store, SessionCredentialPort credentials, PasswordVerificationPort verifier, PasswordHashPort hashes, CompromisedPasswordPort compromised, PasswordNormalizer normalizer, TransactionRunner transactions, Clock clock) {
    this.store = store; this.lookup = new RefreshCredentialLookup(credentials); this.credentials = credentials; this.verifier = verifier; this.hashes = hashes; this.compromised = compromised; this.normalizer = normalizer; this.transactions = transactions; this.clock = clock;
  }

  @Override
  public void change(ChangePasswordCommand command) {
    if (command == null || command.refreshCredential() == null) throw new IllegalArgumentException("invalid request");
    String next = normalizer.normalize(command.newPassword());
    PasswordPolicy.validate(next);
    compromised.requireNotCompromised(next);
    transactions.required(() -> {
      LockedRefreshCredential session = lookup.lock(store, command.refreshCredential()).orElseThrow();
      var current = store.findLocalCredential(session.userId()).orElseThrow();
      if (!verifier.matches(normalizer.normalize(command.currentPassword()), current.passwordHash())) throw new IllegalArgumentException("invalid credentials");
      store.updatePasswordHash(session.userId(), hashes.hash(next), clock.instant());
      store.revokeAllFamilies(session.userId(), RefreshFamilyRevocationReason.PASSWORD_CHANGED, clock.instant());
      return null;
    });
  }
}
