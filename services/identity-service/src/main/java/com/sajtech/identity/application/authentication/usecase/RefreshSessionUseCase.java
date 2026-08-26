package com.sajtech.identity.application.authentication.usecase;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.in.RefreshSession;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class RefreshSessionUseCase implements RefreshSession {
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private final RefreshCredentialLookup lookup;
  private final SessionCredentialPort credentials;
  private final TransactionRunner transactions;
  private final AuthenticationStore store;
  private final Clock clock;

  public RefreshSessionUseCase(
      RefreshCredentialLookup lookup,
      SessionCredentialPort credentials,
      TransactionRunner transactions,
      AuthenticationStore store,
      Clock clock) {
    this.lookup = lookup;
    this.credentials = credentials;
    this.transactions = transactions;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public AuthenticationSession refresh(RefreshSessionCommand command) {
    if (command == null || command.refreshCredential() == null) throw invalidSession();
    GeneratedRefreshCredential next = credentials.newRefreshCredential();
    Instant now = clock.instant();
    Decision decision =
        transactions.required(
            () -> {
              LockedRefreshCredential current =
                  lookup.lock(store, command.refreshCredential()).orElse(null);
              if (current == null || !"ACTIVE".equals(current.familyState())) {
                return Decision.invalid();
              }
              boolean externalOnboarding =
                  "PENDING".equals(current.userStatus())
                      && current.sessionMode()
                          == AuthenticationSessionMode.AUTHENTICATED_ONBOARDING;
              if (!"ACTIVE".equals(current.userStatus()) && !externalOnboarding) {
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
              if (!"ACTIVE".equals(current.credentialState())) {
                store.revokeFamily(
                    current.refreshFamilyId(), RefreshFamilyRevocationReason.REFRESH_REUSE, now);
                return Decision.reuse();
              }
              Instant nextIdle = minimum(now.plus(IDLE_LIFETIME), current.absoluteExpiresAt());
              store.rotateRefresh(current, UUID.randomUUID(), next.digest(), now, nextIdle);
              return Decision.success(current, nextIdle);
            });
    if (decision.kind == DecisionKind.REUSE) {
      throw new AuthenticationException(
          AuthenticationError.REFRESH_REUSE_DETECTED, "Refresh session is invalid");
    }
    if (decision.kind != DecisionKind.SUCCESS) throw invalidSession();
    return new AuthenticationSession(
        decision.current.sessionId(),
        decision.current.refreshFamilyId(),
        decision.current.userId(),
        next.encoded(),
        decision.idleExpiresAt,
        decision.current.absoluteExpiresAt(),
        decision.current.sessionMode(),
        decision.current.selectedTenantId(),
        decision.current.selectedMembershipId());
  }

  private static Instant minimum(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static AuthenticationException invalidSession() {
    return new AuthenticationException(
        AuthenticationError.INVALID_SESSION, "Refresh session is invalid");
  }

  private enum DecisionKind {
    SUCCESS,
    INVALID,
    REUSE
  }

  private static final class Decision {
    private final DecisionKind kind;
    private final LockedRefreshCredential current;
    private final Instant idleExpiresAt;

    private Decision(DecisionKind kind, LockedRefreshCredential current, Instant idleExpiresAt) {
      this.kind = kind;
      this.current = current;
      this.idleExpiresAt = idleExpiresAt;
    }

    static Decision success(LockedRefreshCredential current, Instant idleExpiresAt) {
      return new Decision(DecisionKind.SUCCESS, current, idleExpiresAt);
    }

    static Decision invalid() {
      return new Decision(DecisionKind.INVALID, null, null);
    }

    static Decision reuse() {
      return new Decision(DecisionKind.REUSE, null, null);
    }
  }
}
