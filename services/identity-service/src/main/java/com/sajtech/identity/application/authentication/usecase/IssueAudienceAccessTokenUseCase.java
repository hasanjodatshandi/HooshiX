package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.*;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.IssueAudienceAccessToken;
import com.sajtech.identity.application.authentication.port.out.*;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.Set;

public final class IssueAudienceAccessTokenUseCase implements IssueAudienceAccessToken {
  private final Set<String> allowedAudiences;
  private final RefreshCredentialLookup lookup;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final TenantContextValidationPort tenants;
  private final AccessTokenSigner signer;
  private final Clock clock;

  public IssueAudienceAccessTokenUseCase(
      Set<String> allowedAudiences,
      RefreshCredentialLookup lookup,
      TransactionRunner transactions,
      AuthenticationStore store,
      TenantContextValidationPort tenants,
      AccessTokenSigner signer,
      Clock clock) {
    this.allowedAudiences = Set.copyOf(allowedAudiences);
    if (this.allowedAudiences.stream().anyMatch(a -> a == null || a.isBlank() || a.contains("*")))
      throw new IllegalArgumentException("Audience allow-list is invalid");
    this.lookup = lookup;
    this.transactions = transactions;
    this.store = store;
    this.tenants = tenants;
    this.signer = signer;
    this.clock = clock;
  }

  @Override
  public SignedAccessToken issue(IssueAudienceAccessTokenCommand command) {
    if (command == null
        || command.audience() == null
        || !allowedAudiences.contains(command.audience()))
      throw new AuthenticationException(
          AuthenticationError.AUDIENCE_NOT_ALLOWED, "Requested audience is not allowed");
    Instant now = clock.instant();
    Decision d =
        transactions.required(
            () -> {
              LockedRefreshCredential current =
                  lookup.lock(store, command.refreshCredential()).orElse(null);
              if (current == null || !"ACTIVE".equals(current.familyState()))
                return Decision.invalid();
              if (!"ACTIVE".equals(current.credentialState())) {
                store.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.REFRESH_REUSE, now);
                return Decision.reuse();
              }
              if (!"ACTIVE".equals(current.userStatus())) {
                store.revokeAllFamilies(
                    current.userId(), RefreshFamilyRevocationReason.USER_INACTIVE, now);
                return Decision.invalid();
              }
              if (!now.isBefore(current.idleExpiresAt())
                  || !now.isBefore(current.absoluteExpiresAt())) {
                store.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.EXPIRED, now);
                return Decision.invalid();
              }
              if (current.sessionMode() == AuthenticationSessionMode.AUTHENTICATED_ONBOARDING)
                return Decision.onboarding();
              if (current.sessionMode() != AuthenticationSessionMode.TENANT_AUTHENTICATED
                  || current.selectedTenantId() == null
                  || current.selectedMembershipId() == null
                  || !tenants.isSelectable(
                      current.userId(), current.selectedTenantId(), current.selectedMembershipId()))
                return Decision.invalid();
              SignedAccessToken token =
                  signer.sign(
                      new AccessTokenContext(
                          current.userId(),
                          current.selectedTenantId(),
                          current.selectedMembershipId(),
                          current.sessionId(),
                          command.audience(),
                          now));
              return Decision.success(token);
            });
    if (d.kind == Kind.REUSE)
      throw new AuthenticationException(
          AuthenticationError.REFRESH_REUSE_DETECTED, "Refresh session is invalid");
    if (d.kind == Kind.INVALID)
      throw new AuthenticationException(
          AuthenticationError.INVALID_SESSION, "Refresh session is invalid");
    if (d.kind == Kind.ONBOARDING)
      throw new AuthenticationException(
          AuthenticationError.TENANT_SELECTION_REQUIRED, "Tenant selection is required");
    return d.token;
  }

  private enum Kind {
    SUCCESS,
    ONBOARDING,
    INVALID,
    REUSE
  }

  private record Decision(Kind kind, SignedAccessToken token) {
    static Decision success(SignedAccessToken t) {
      return new Decision(Kind.SUCCESS, t);
    }

    static Decision onboarding() {
      return new Decision(Kind.ONBOARDING, null);
    }

    static Decision invalid() {
      return new Decision(Kind.INVALID, null);
    }

    static Decision reuse() {
      return new Decision(Kind.REUSE, null);
    }
  }
}
