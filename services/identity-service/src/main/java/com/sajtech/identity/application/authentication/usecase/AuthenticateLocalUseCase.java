package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.AuthenticateLocal;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.registration.RegistrationException;
import com.sajtech.identity.application.registration.service.ContactCanonicalizer;
import com.sajtech.identity.application.registration.service.PasswordNormalizer;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class AuthenticateLocalUseCase implements AuthenticateLocal {
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
  private static final int ACTIVE_FAMILY_LIMIT = 20;
  private final ContactCanonicalizer contacts;
  private final PasswordNormalizer passwords;
  private final LoginQuotaPort quota;
  private final PasswordVerificationPort verifier;
  private final SessionCredentialPort credentials;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final AuthenticationTenantSelectionPort tenantSelection;
  private final Clock clock;

  public AuthenticateLocalUseCase(
      ContactCanonicalizer contacts,
      PasswordNormalizer passwords,
      LoginQuotaPort quota,
      PasswordVerificationPort verifier,
      SessionCredentialPort credentials,
      TransactionRunner transactions,
      AuthenticationStore store,
      AuthenticationTenantSelectionPort tenantSelection,
      Clock clock) {
    this.contacts = contacts;
    this.passwords = passwords;
    this.quota = quota;
    this.verifier = verifier;
    this.credentials = credentials;
    this.transactions = transactions;
    this.store = store;
    this.tenantSelection = tenantSelection;
    this.clock = clock;
  }

  public AuthenticateLocalUseCase(
      ContactCanonicalizer contacts,
      PasswordNormalizer passwords,
      LoginQuotaPort quota,
      PasswordVerificationPort verifier,
      SessionCredentialPort credentials,
      TransactionRunner transactions,
      AuthenticationStore store,
      Clock clock) {
    this(
        contacts,
        passwords,
        quota,
        verifier,
        credentials,
        transactions,
        store,
        userId -> AuthenticationTenantSelection.onboarding(),
        clock);
  }

  @Override
  public AuthenticationSession authenticate(AuthenticateLocalCommand command) {
    if (command == null) throw invalid();
    quota.checkSource(command.clientAddress());
    CanonicalContact contact;
    String password;
    try {
      contact = contacts.canonicalize(command.channel(), command.contact());
      password = passwords.normalize(command.password());
    } catch (RegistrationException exception) {
      throw invalid();
    }
    LocalCredentialRecord found = store.findVerifiedLocalCredential(contact).orElse(null);
    boolean proof = verifier.matches(password, found == null ? null : found.passwordHash());
    if (!proof || found == null) {
      quota.recordFailure(contact);
      throw invalidCredentials();
    }
    quota.recordSuccess(contact);
    if (!"ACTIVE".equals(found.userStatus())) throw invalidCredentials();

    Instant now = clock.instant();
    AuthenticationTenantSelection selection =
        tenantSelection.resolveAfterPrimaryAuthentication(found.userId());
    GeneratedRefreshCredential refresh = credentials.newRefreshCredential();
    PreparedSession prepared =
        new PreparedSession(
            UUID.randomUUID(),
            credentials.newSessionId(),
            found.userId(),
            UUID.randomUUID(),
            refresh.digest(),
            now,
            now,
            now.plus(IDLE_LIFETIME),
            now.plus(ABSOLUTE_LIFETIME),
            selection.mode(),
            selection.tenantId(),
            selection.membershipId());

    transactions.required(
        () -> {
          LocalCredentialRecord locked =
              store
                  .lockVerifiedLocalCredential(found.userId(), contact)
                  .orElseThrow(AuthenticateLocalUseCase::invalidCredentials);
          if (!"ACTIVE".equals(locked.userStatus())
              || !sameHash(found.passwordHash(), locked.passwordHash())) {
            throw invalidCredentials();
          }
          store.expireDueFamilies(found.userId(), now);
          int active = store.countActiveFamilies(found.userId());
          if (active < 0 || active > ACTIVE_FAMILY_LIMIT) {
            throw new AuthenticationException(
                AuthenticationError.SESSION_STATE_INVALID, "Session family state is invalid");
          }
          if (active == ACTIVE_FAMILY_LIMIT) {
            UUID oldest =
                store
                    .oldestActiveFamily(found.userId())
                    .orElseThrow(
                        () ->
                            new AuthenticationException(
                                AuthenticationError.SESSION_STATE_INVALID,
                                "Session family state is invalid"));
            store.revokeFamily(oldest, RefreshFamilyRevocationReason.ACTIVE_FAMILY_LIMIT, now);
          }
          store.createSession(prepared);
          return null;
        });

    return new AuthenticationSession(
        prepared.sessionId(),
        prepared.refreshFamilyId(),
        prepared.userId(),
        refresh.encoded(),
        prepared.idleExpiresAt(),
        prepared.absoluteExpiresAt(),
        prepared.mode(),
        prepared.selectedTenantId(),
        prepared.selectedMembershipId());
  }

  private static boolean sameHash(String expected, String actual) {
    if (expected == null || actual == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
  }

  private static AuthenticationException invalid() {
    return new AuthenticationException(
        AuthenticationError.INVALID_ARGUMENT, "Authentication input is invalid");
  }

  private static AuthenticationException invalidCredentials() {
    return new AuthenticationException(
        AuthenticationError.INVALID_CREDENTIALS, "Authentication credentials are invalid");
  }
}
