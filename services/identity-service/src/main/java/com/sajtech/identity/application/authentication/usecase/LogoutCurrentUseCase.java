package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.LogoutCurrent;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;

public final class LogoutCurrentUseCase implements LogoutCurrent {
  private final RefreshCredentialLookup lookup;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final Clock clock;

  public LogoutCurrentUseCase(
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
  public void logout(LogoutCurrentCommand command) {
    if (command == null) return;
    transactions.required(
        () -> {
          LockedRefreshCredential current =
              lookup.lock(store, command.refreshCredential()).orElse(null);
          if (current == null || !"ACTIVE".equals(current.familyState())) return null;
          RefreshFamilyRevocationReason reason =
              "ACTIVE".equals(current.credentialState())
                  ? RefreshFamilyRevocationReason.LOGOUT_CURRENT
                  : RefreshFamilyRevocationReason.REFRESH_REUSE;
          store.revokeFamily(current.refreshFamilyId(), reason, clock.instant());
          return null;
        });
  }
}
