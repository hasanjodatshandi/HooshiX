package com.sajtech.identity.application.erasure.usecase;

import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.erasure.*;
import com.sajtech.identity.application.erasure.model.LegalHoldView;
import com.sajtech.identity.application.erasure.port.in.LegalHoldManagement;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.out.*;
import com.sajtech.identity.application.tenant.port.out.AuthorizationTenantPort;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.*;
import java.util.OptionalLong;
import java.util.UUID;

public final class LegalHoldUseCase implements LegalHoldManagement {
  private static final Duration RECENT_AUTH = Duration.ofMinutes(5);
  private final ErasureStore store;
  private final AuthenticationStore authentication;
  private final RefreshCredentialLookup lookup;
  private final MfaStore mfa;
  private final MfaCryptographyPort cryptography;
  private final AuthorizationTenantPort authorization;
  private final TransactionRunner transactions;
  private final Clock clock;

  public LegalHoldUseCase(
      ErasureStore store,
      AuthenticationStore authentication,
      SessionCredentialPort credentials,
      MfaStore mfa,
      MfaCryptographyPort cryptography,
      AuthorizationTenantPort authorization,
      TransactionRunner transactions,
      Clock clock) {
    this.store = store;
    this.authentication = authentication;
    lookup = new RefreshCredentialLookup(credentials);
    this.mfa = mfa;
    this.cryptography = cryptography;
    this.authorization = authorization;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public LegalHoldView create(
      UUID requestId,
      String refresh,
      UUID erasureRequestId,
      String authorityReference,
      MfaProof proof) {
    LockedRefreshCredential observed = observed(refresh);
    authorization.checkPlatformPermission(observed.userId(), "platform.legal_hold.manage");
    return transactions.required(
        () -> {
          LockedRefreshCredential locked = locked(refresh, observed);
          requireRecent(locked, clock.instant());
          verifyProof(locked, proof);
          return store.createHold(
              requestId, erasureRequestId, authorityReference, locked.userId(), clock.instant());
        });
  }

  @Override
  public LegalHoldView release(UUID requestId, String refresh, UUID holdId, MfaProof proof) {
    LockedRefreshCredential observed = observed(refresh);
    authorization.checkPlatformPermission(observed.userId(), "platform.legal_hold.manage");
    return transactions.required(
        () -> {
          LockedRefreshCredential locked = locked(refresh, observed);
          requireRecent(locked, clock.instant());
          verifyProof(locked, proof);
          return store.releaseHold(holdId, locked.userId(), clock.instant());
        });
  }

  private LockedRefreshCredential observed(String refresh) {
    LockedRefreshCredential x =
        lookup.find(authentication, refresh).orElseThrow(LegalHoldUseCase::invalidSession);
    requireRecent(x, clock.instant());
    return x;
  }

  private LockedRefreshCredential locked(String refresh, LockedRefreshCredential observed) {
    LockedRefreshCredential x =
        lookup.lock(authentication, refresh).orElseThrow(LegalHoldUseCase::invalidSession);
    if (!x.userId().equals(observed.userId()) || !x.credentialId().equals(observed.credentialId()))
      throw invalidSession();
    return x;
  }

  private void verifyProof(LockedRefreshCredential session, MfaProof proof) {
    if (!mfa.requiresMfa(session.userId()))
      throw new ErasureException(
          ErasureError.MFA_PROOF_REQUIRED, "An active MFA factor is required");
    var active =
        mfa.lockActiveEnrollment(session.userId())
            .orElseThrow(
                () ->
                    new ErasureException(ErasureError.MFA_PROOF_REQUIRED, "MFA proof is required"));
    if (proof == null || !accept(active, proof, clock.instant()))
      throw new ErasureException(ErasureError.MFA_PROOF_INVALID, "MFA proof is invalid");
  }

  private boolean accept(MfaStore.ActiveEnrollment active, MfaProof proof, Instant now) {
    if (proof.type() == MfaProofType.TOTP) {
      OptionalLong step =
          cryptography.verifyTotp(
              active.userId(), active.enrollmentId(), active.secret(), proof.code(), now);
      if (step.isEmpty()
          || (active.lastAcceptedTimestep() != null
              && step.getAsLong() <= active.lastAcceptedTimestep())) return false;
      mfa.acceptTotp(active.enrollmentId(), step.getAsLong(), now);
      return true;
    }
    return proof.type() == MfaProofType.RECOVERY_CODE
        && mfa.consumeRecoveryCode(
            active.userId(),
            active.enrollmentId(),
            cryptography.recoveryDigestCandidates(active.enrollmentId(), proof.code()),
            now);
  }

  private static void requireRecent(LockedRefreshCredential s, Instant now) {
    if (!"ACTIVE".equals(s.credentialState())
        || !"ACTIVE".equals(s.familyState())
        || !"ACTIVE".equals(s.userStatus())
        || !now.isBefore(s.idleExpiresAt())
        || !now.isBefore(s.absoluteExpiresAt())) throw invalidSession();
    if (s.authenticatedAt().plus(RECENT_AUTH).isBefore(now))
      throw new ErasureException(
          ErasureError.RECENT_AUTHENTICATION_REQUIRED, "Recent authentication is required");
  }

  private static ErasureException invalidSession() {
    return new ErasureException(ErasureError.INVALID_SESSION, "Legal hold session is invalid");
  }
}
