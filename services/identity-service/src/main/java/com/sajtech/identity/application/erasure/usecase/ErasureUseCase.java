package com.sajtech.identity.application.erasure.usecase;

import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshFamilyRevocationReason;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.erasure.ErasureError;
import com.sajtech.identity.application.erasure.ErasureException;
import com.sajtech.identity.application.erasure.model.ErasureRequestView;
import com.sajtech.identity.application.erasure.port.in.ErasureCoordination;
import com.sajtech.identity.application.erasure.port.in.RequestSelfErasureCommand;
import com.sajtech.identity.application.erasure.port.out.ErasureStore;
import com.sajtech.identity.application.mfa.model.MfaProof;
import com.sajtech.identity.application.mfa.model.MfaProofType;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.application.mfa.port.out.MfaStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

public final class ErasureUseCase implements ErasureCoordination {
  private static final Duration RECENT_AUTH = Duration.ofMinutes(5);
  private final ErasureStore erasure;
  private final AuthenticationStore authentication;
  private final RefreshCredentialLookup refreshLookup;
  private final MfaStore mfa;
  private final MfaCryptographyPort mfaCryptography;
  private final TransactionRunner transactions;
  private final Clock clock;

  public ErasureUseCase(
      ErasureStore erasure,
      AuthenticationStore authentication,
      SessionCredentialPort sessionCredentials,
      MfaStore mfa,
      MfaCryptographyPort mfaCryptography,
      TransactionRunner transactions,
      Clock clock) {
    this.erasure = erasure;
    this.authentication = authentication;
    this.refreshLookup = new RefreshCredentialLookup(sessionCredentials);
    this.mfa = mfa;
    this.mfaCryptography = mfaCryptography;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public ErasureRequestView requestSelfErasure(RequestSelfErasureCommand command) {
    if (command == null || !"ERASE_MY_ACCOUNT".equals(command.confirmation())) throw invalid();
    Instant now = clock.instant();
    LockedRefreshCredential observed =
        refreshLookup
            .find(authentication, command.refreshCredential())
            .orElseThrow(ErasureUseCase::invalidSession);
    var existing = erasure.find(command.requestId());
    if (existing.isPresent()) {
      if (!existing.get().userId().equals(observed.userId())) {
        throw new ErasureException(ErasureError.REQUEST_CONFLICT, "Erasure request conflicts");
      }
      return existing.get();
    }
    requireRecentUsable(observed, now);

    return transactions.required(
        () -> {
          var replay = erasure.find(command.requestId());
          if (replay.isPresent()) {
            if (!replay.get().userId().equals(observed.userId())) {
              throw new ErasureException(
                  ErasureError.REQUEST_CONFLICT, "Erasure request conflicts");
            }
            return replay.get();
          }
          LockedRefreshCredential locked =
              refreshLookup
                  .lock(authentication, command.refreshCredential())
                  .orElseThrow(ErasureUseCase::invalidSession);
          if (!sameSession(observed, locked)) throw invalidSession();
          requireRecentUsable(locked, now);
          if (mfa.requiresMfa(locked.userId())) {
            var active =
                mfa.lockActiveEnrollment(locked.userId())
                    .orElseThrow(
                        () ->
                            new ErasureException(
                                ErasureError.MFA_PROOF_REQUIRED, "MFA proof is required"));
            if (!acceptProof(active, command.mfaProof(), now)) {
              throw new ErasureException(ErasureError.MFA_PROOF_INVALID, "MFA proof is invalid");
            }
          }
          ErasureRequestView accepted = erasure.accept(command.requestId(), locked.userId(), now);
          authentication.revokeAllFamilies(
              locked.userId(), RefreshFamilyRevocationReason.ERASURE_REQUESTED, now);
          return accepted;
        });
  }

  private boolean acceptProof(MfaStore.ActiveEnrollment active, MfaProof proof, Instant now) {
    if (proof == null) return false;
    if (proof.type() == MfaProofType.TOTP) {
      OptionalLong timestep =
          mfaCryptography.verifyTotp(
              active.userId(), active.enrollmentId(), active.secret(), proof.code(), now);
      if (timestep.isEmpty()
          || (active.lastAcceptedTimestep() != null
              && timestep.getAsLong() <= active.lastAcceptedTimestep())) return false;
      mfa.acceptTotp(active.enrollmentId(), timestep.getAsLong(), now);
      return true;
    }
    if (proof.type() == MfaProofType.RECOVERY_CODE) {
      return mfa.consumeRecoveryCode(
          active.userId(),
          active.enrollmentId(),
          mfaCryptography.recoveryDigestCandidates(active.enrollmentId(), proof.code()),
          now);
    }
    return false;
  }

  private static void requireRecentUsable(LockedRefreshCredential session, Instant now) {
    if (!"ACTIVE".equals(session.credentialState())
        || !"ACTIVE".equals(session.familyState())
        || !"ACTIVE".equals(session.userStatus())
        || !now.isBefore(session.idleExpiresAt())
        || !now.isBefore(session.absoluteExpiresAt())) throw invalidSession();
    if (session.authenticatedAt().plus(RECENT_AUTH).isBefore(now)) {
      throw new ErasureException(
          ErasureError.RECENT_AUTHENTICATION_REQUIRED, "Recent authentication is required");
    }
  }

  private static boolean sameSession(
      LockedRefreshCredential observed, LockedRefreshCredential locked) {
    return observed.userId().equals(locked.userId())
        && observed.refreshFamilyId().equals(locked.refreshFamilyId())
        && observed.credentialId().equals(locked.credentialId());
  }

  private static ErasureException invalid() {
    return new ErasureException(ErasureError.INVALID_ARGUMENT, "Erasure request is invalid");
  }

  private static ErasureException invalidSession() {
    return new ErasureException(ErasureError.INVALID_SESSION, "Erasure session is invalid");
  }
}
