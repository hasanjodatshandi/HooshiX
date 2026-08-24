package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.password.PasswordError;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.model.PasswordPolicy;
import com.sajtech.identity.application.password.port.in.ConfirmPasswordRecovery;
import com.sajtech.identity.application.password.port.in.ConfirmPasswordRecoveryCommand;
import com.sajtech.identity.application.password.port.out.PasswordRecoverySecretPort;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.application.registration.port.out.CompromisedPasswordPort;
import com.sajtech.identity.application.registration.port.out.PasswordHashPort;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Clock;
import java.time.Instant;

public final class ConfirmPasswordRecoveryUseCase implements ConfirmPasswordRecovery {
  private final PasswordRecoveryStore recovery;
  private final PasswordRecoverySecretPort secrets;
  private final AuthenticationStore authentication;
  private final PasswordHashPort hashes;
  private final CompromisedPasswordPort compromised;
  private final PasswordNormalizer passwords;
  private final ContactCanonicalizer contacts;
  private final SemanticQuotaPort quota;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ConfirmPasswordRecoveryUseCase(
      PasswordRecoveryStore recovery,
      PasswordRecoverySecretPort secrets,
      AuthenticationStore authentication,
      PasswordHashPort hashes,
      CompromisedPasswordPort compromised,
      PasswordNormalizer passwords,
      ContactCanonicalizer contacts,
      SemanticQuotaPort quota,
      TransactionRunner transactions,
      Clock clock) {
    this.recovery = recovery;
    this.secrets = secrets;
    this.authentication = authentication;
    this.hashes = hashes;
    this.compromised = compromised;
    this.passwords = passwords;
    this.contacts = contacts;
    this.quota = quota;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public void confirm(ConfirmPasswordRecoveryCommand command) {
    if (command == null
        || command.requestId() == null
        || command.channel() == null
        || !validAddress(command.clientAddress())
        || !validCode(command.code())) {
      throw invalid();
    }
    CanonicalContact contact;
    String next;
    try {
      contact = contacts.canonicalize(command.channel(), command.contact());
      next = passwords.normalize(command.newPassword());
      PasswordPolicy.validate(next);
    } catch (RuntimeException exception) {
      throw invalid();
    }
    quota.consume(
        new QuotaRequest(
            QuotaOperation.CONFIRM_PASSWORD_RECOVERY, contact, command.clientAddress()));
    if (recovery.confirmationAlreadyCompleted(command.requestId())) return;

    Instant now = clock.instant();
    var observed =
        recovery
            .findActiveByContact(contact.canonicalValue(), now)
            .orElseThrow(ConfirmPasswordRecoveryUseCase::invalidProof);
    if (!secrets.matches(observed.id(), command.code(), observed.verifier(), observed.keyId())) {
      recordFailedProof(contact, observed.id(), command.code(), now);
      throw invalidProof();
    }

    compromised.requireNotCompromised(next);
    String nextHash = hashes.hash(next);
    Outcome outcome =
        transactions.required(
            () -> {
              if (recovery.confirmationAlreadyCompleted(command.requestId())) {
                return Outcome.COMPLETED;
              }
              var target = recovery.lockTargetByContact(contact);
              if (target.isEmpty() || !target.get().userId().equals(observed.userId())) {
                return Outcome.INVALID;
              }
              var locked = recovery.lockActiveByContact(contact.canonicalValue(), now).orElse(null);
              if (locked == null || !locked.id().equals(observed.id())) return Outcome.INVALID;
              if (!secrets.matches(
                  locked.id(), command.code(), locked.verifier(), locked.keyId())) {
                recovery.recordFailedProof(locked.id(), now);
                return Outcome.INVALID;
              }
              authentication.updatePasswordHash(locked.userId(), nextHash, now);
              authentication.revokeAllFamilies(
                  locked.userId(), RefreshFamilyRevocationReason.PASSWORD_CHANGED, now);
              recovery.markUsed(locked.id(), command.requestId(), now);
              return Outcome.COMPLETED;
            });
    if (outcome != Outcome.COMPLETED) throw invalidProof();
  }

  private void recordFailedProof(
      CanonicalContact contact, java.util.UUID observedId, String code, Instant now) {
    transactions.required(
        () -> {
          var locked = recovery.lockActiveByContact(contact.canonicalValue(), now).orElse(null);
          if (locked != null
              && locked.id().equals(observedId)
              && !secrets.matches(locked.id(), code, locked.verifier(), locked.keyId())) {
            recovery.recordFailedProof(locked.id(), now);
          }
          return null;
        });
  }

  private enum Outcome {
    COMPLETED,
    INVALID
  }

  private static PasswordException invalid() {
    return new PasswordException(
        PasswordError.INVALID_ARGUMENT, "Password recovery confirmation is invalid");
  }

  private static PasswordException invalidProof() {
    return new PasswordException(
        PasswordError.INVALID_RECOVERY_PROOF, "Password recovery proof is invalid");
  }

  private static boolean validAddress(byte[] address) {
    return address != null && (address.length == 4 || address.length == 16);
  }

  private static boolean validCode(String code) {
    return code != null
        && code.length() == 8
        && code.chars().allMatch(character -> character >= '0' && character <= '9');
  }
}
