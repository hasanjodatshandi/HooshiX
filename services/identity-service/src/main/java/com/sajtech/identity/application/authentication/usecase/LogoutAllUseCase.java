package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.LogoutAll;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;

public final class LogoutAllUseCase implements LogoutAll {
  private final RefreshCredentialLookup lookup;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final Clock clock;

  public LogoutAllUseCase(
      RefreshCredentialLookup lookup,
      TransactionRunner transactions,
      AuthenticationStore store,
      Clock clock) {
    this.lookup = lookup;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void logoutAll(LogoutAllCommand command) {
    if (command == null || command.refreshCredential() == null) throw invalid();
    boolean completed =
        transactions.required(
            () -> {
              LockedRefreshCredential current =
                  lookup.lock(store, command.refreshCredential()).orElse(null);
              if (current == null || !"ACTIVE".equals(current.familyState())) return false;
              if (!"ACTIVE".equals(current.credentialState())) {
                store.revokeFamily(
                    current.refreshFamilyId(),
                    RefreshFamilyRevocationReason.REFRESH_REUSE,
                    clock.instant());
                return false;
              }
              if (!"ACTIVE".equals(current.userStatus())) {
                store.revokeAllFamilies(
                    current.userId(), RefreshFamilyRevocationReason.USER_INACTIVE, clock.instant());
                return false;
              }
              store.revokeAllFamilies(
                  current.userId(), RefreshFamilyRevocationReason.LOGOUT_ALL, clock.instant());
              return true;
            });
    if (!completed) throw invalid();
  }

  private static AuthenticationException invalid() {
    return new AuthenticationException(
        AuthenticationError.INVALID_SESSION, "Refresh session is invalid");
  }
}
