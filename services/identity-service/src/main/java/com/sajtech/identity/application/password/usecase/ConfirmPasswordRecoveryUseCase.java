package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.password.model.PasswordPolicy;
import com.sajtech.identity.application.password.port.in.*;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.registration.port.out.*;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;

public final class ConfirmPasswordRecoveryUseCase implements ConfirmPasswordRecovery {
  private final PasswordRecoveryStore recovery;
  private final ChallengeSecretPort secrets;
  private final AuthenticationStore auth;
  private final PasswordHashPort hashes;
  private final PasswordNormalizer normalizer;
  private final TransactionRunner tx;
  private final Clock clock;

  public ConfirmPasswordRecoveryUseCase(
      PasswordRecoveryStore recovery,
      ChallengeSecretPort secrets,
      AuthenticationStore auth,
      PasswordHashPort hashes,
      PasswordNormalizer normalizer,
      TransactionRunner tx,
      Clock clock) {
    this.recovery = recovery;
    this.secrets = secrets;
    this.auth = auth;
    this.hashes = hashes;
    this.normalizer = normalizer;
    this.tx = tx;
    this.clock = clock;
  }

  public void confirm(ConfirmPasswordRecoveryCommand c) {
    var ch = recovery.find(java.util.UUID.fromString(c.code())).orElseThrow();
    if (ch.expiresAt().isBefore(clock.instant())
        || !secrets.matches(ch.id(), c.code(), ch.verifier(), ch.keyId()))
      throw new IllegalArgumentException("invalid recovery");
    var pwd = normalizer.normalize(c.newPassword());
    PasswordPolicy.validate(pwd);
    tx.required(
        () -> {
          auth.updatePasswordHash(ch.userId(), hashes.hash(pwd), clock.instant());
          auth.revokeAllFamilies(
              ch.userId(), RefreshFamilyRevocationReason.PASSWORD_CHANGED, clock.instant());
          recovery.markUsed(ch.id(), clock.instant());
          return null;
        });
  }
}
