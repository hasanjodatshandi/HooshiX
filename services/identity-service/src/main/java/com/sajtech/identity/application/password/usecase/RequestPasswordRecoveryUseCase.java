package com.sajtech.identity.application.password.usecase;

import com.sajtech.identity.application.password.PasswordError;
import com.sajtech.identity.application.password.PasswordException;
import com.sajtech.identity.application.password.port.in.RequestPasswordRecovery;
import com.sajtech.identity.application.password.port.in.RequestPasswordRecoveryCommand;
import com.sajtech.identity.application.password.port.out.PasswordRecoverySecretPort;
import com.sajtech.identity.application.password.port.out.PasswordRecoveryStore;
import com.sajtech.identity.application.password.port.out.PreparedPasswordRecovery;
import com.sajtech.identity.application.registration.model.QuotaOperation;
import com.sajtech.identity.application.registration.model.QuotaRequest;
import com.sajtech.identity.application.registration.port.out.NotificationEscrowPort;
import com.sajtech.identity.application.registration.port.out.SemanticQuotaPort;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import com.sajtech.identity.domain.registration.valueobject.RegistrationLocale;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class RequestPasswordRecoveryUseCase implements RequestPasswordRecovery {
  private static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(10);
  private final PasswordRecoveryStore store;
  private final PasswordRecoverySecretPort secrets;
  private final NotificationEscrowPort escrow;
  private final ContactCanonicalizer contacts;
  private final SemanticQuotaPort quota;
  private final TransactionRunner transactions;
  private final Clock clock;

  public RequestPasswordRecoveryUseCase(
      PasswordRecoveryStore store,
      PasswordRecoverySecretPort secrets,
      NotificationEscrowPort escrow,
      ContactCanonicalizer contacts,
      SemanticQuotaPort quota,
      TransactionRunner transactions,
      Clock clock) {
    this.store = store;
    this.secrets = secrets;
    this.escrow = escrow;
    this.contacts = contacts;
    this.quota = quota;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public void request(RequestPasswordRecoveryCommand command) {
    if (command == null
        || command.requestId() == null
        || command.channel() == null
        || !validAddress(command.clientAddress())) {
      throw invalid();
    }
    CanonicalContact contact;
    try {
      contact = contacts.canonicalize(command.channel(), command.contact());
    } catch (RuntimeException exception) {
      throw invalid();
    }
    quota.consume(
        new QuotaRequest(
            QuotaOperation.REQUEST_PASSWORD_RECOVERY, contact, command.clientAddress()));
    var target = store.findTargetByContact(contact);
    if (target.isEmpty()) return;

    Instant now = clock.instant();
    UUID challengeId = UUID.randomUUID();
    UUID outboxId = UUID.randomUUID();
    var generated = secrets.generate(challengeId);
    Instant expiresAt = now.plus(CHALLENGE_LIFETIME);
    var encrypted =
        escrow.encrypt(outboxId, target.get().contact(), RegistrationLocale.FA, generated.code());
    PreparedPasswordRecovery prepared =
        new PreparedPasswordRecovery(
            command.requestId(),
            challengeId,
            outboxId,
            UUID.randomUUID(),
            target.get().userId(),
            target.get().contactId(),
            target.get().contact(),
            RegistrationLocale.FA,
            generated.verifier(),
            generated.keyId(),
            encrypted,
            now,
            expiresAt);
    transactions.required(
        () -> {
          if (store.requestAlreadyAccepted(command.requestId())) return null;
          var lockedTarget = store.lockTargetByContact(contact);
          if (lockedTarget.isEmpty()
              || !lockedTarget.get().userId().equals(target.get().userId())
              || !lockedTarget.get().contactId().equals(target.get().contactId())) return null;
          var active = store.lockActiveByContact(contact.canonicalValue(), now);
          if (active.isPresent()
              && active.get().expiresAt().minus(CHALLENGE_LIFETIME).plusSeconds(60).isAfter(now))
            return null;
          store.create(prepared);
          return null;
        });
  }

  private static PasswordException invalid() {
    return new PasswordException(
        PasswordError.INVALID_ARGUMENT, "Password recovery request is invalid");
  }

  private static boolean validAddress(byte[] address) {
    return address != null && (address.length == 4 || address.length == 16);
  }
}
