package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.IssueAudienceAccessToken;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

public final class IssueAudienceAccessTokenUseCase implements IssueAudienceAccessToken {
  private final Set<String> allowedAudiences;
  private final RefreshCredentialLookup lookup;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final Clock clock;

  public IssueAudienceAccessTokenUseCase(
      Set<String> allowedAudiences,
      RefreshCredentialLookup lookup,
      TransactionRunner transactions,
      AuthenticationStore store,
      Clock clock) {
    this.allowedAudiences = Set.copyOf(allowedAudiences);
    if (this.allowedAudiences.stream().anyMatch(a -> a == null || a.isBlank() || a.contains("*"))) {
      throw new IllegalArgumentException("Audience allow-list is invalid");
    }
    this.lookup = lookup;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public SignedAccessToken issue(IssueAudienceAccessTokenCommand command) {
    if (command == null
        || command.audience() == null
        || !allowedAudiences.contains(command.audience())) {
      throw new AuthenticationException(
          AuthenticationError.AUDIENCE_NOT_ALLOWED, "Requested audience is not allowed");
    }
    Instant now = clock.instant();
    Validation validation =
        transactions.required(
            () -> {
              LockedRefreshCredential current =
                  lookup.lock(store, command.refreshCredential()).orElse(null);
              if (current == null || !"ACTIVE".equals(current.familyState())) {
                return Validation.INVALID;
              }
              if (!"ACTIVE".equals(current.credentialState())) {
                store.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.REFRESH_REUSE, now);
                return Validation.REUSE;
              }
              if (!"ACTIVE".equals(current.userStatus())) {
                store.revokeAllFamilies(
                    current.userId(), RefreshFamilyRevocationReason.USER_INACTIVE, now);
                return Validation.INVALID;
              }
              if (!now.isBefore(current.idleExpiresAt())
                  || !now.isBefore(current.absoluteExpiresAt())) {
                store.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.EXPIRED, now);
                return Validation.INVALID;
              }
              return current.sessionMode() == AuthenticationSessionMode.AUTHENTICATED_ONBOARDING
                  ? Validation.ONBOARDING
                  : Validation.INVALID;
            });
    if (validation == Validation.REUSE) {
      throw new AuthenticationException(
          AuthenticationError.REFRESH_REUSE_DETECTED, "Refresh session is invalid");
    }
    if (validation == Validation.INVALID) {
      throw new AuthenticationException(
          AuthenticationError.INVALID_SESSION, "Refresh session is invalid");
    }
    throw new AuthenticationException(
        AuthenticationError.TENANT_SELECTION_REQUIRED, "Tenant selection is required");
  }

  private enum Validation {
    ONBOARDING,
    INVALID,
    REUSE
  }
}
