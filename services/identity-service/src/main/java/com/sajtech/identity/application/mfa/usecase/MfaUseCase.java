package com.sajtech.identity.application.mfa.usecase;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.AuthenticationTenantSelectionPort;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import com.sajtech.identity.application.authentication.service.RefreshCredentialLookup;
import com.sajtech.identity.application.mfa.MfaError;
import com.sajtech.identity.application.mfa.MfaException;
import com.sajtech.identity.application.mfa.model.*;
import com.sajtech.identity.application.mfa.port.in.*;
import com.sajtech.identity.application.mfa.port.out.MfaCryptographyPort;
import com.sajtech.identity.application.mfa.port.out.MfaQuotaPort;
import com.sajtech.identity.application.mfa.port.out.MfaStore;
import com.sajtech.identity.application.transaction.port.out.TransactionRunner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public final class MfaUseCase implements MfaManagement, CompleteMfaAuthentication {
  private static final Duration RECENT_AUTH = Duration.ofMinutes(5);
  private static final Duration ENROLLMENT_LIFETIME = Duration.ofMinutes(10);
  private static final Duration IDLE_LIFETIME = Duration.ofDays(7);
  private static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(30);
  private static final int ACTIVE_FAMILY_LIMIT = 20;
  private final MfaStore mfa;
  private final MfaCryptographyPort cryptography;
  private final MfaQuotaPort quota;
  private final AuthenticationStore authentication;
  private final RefreshCredentialLookup refreshLookup;
  private final SessionCredentialPort sessionCredentials;
  private final AuthenticationTenantSelectionPort tenantSelection;
  private final TransactionRunner transactions;
  private final Clock clock;

  public MfaUseCase(
      MfaStore mfa,
      MfaCryptographyPort cryptography,
      MfaQuotaPort quota,
      AuthenticationStore authentication,
      RefreshCredentialLookup refreshLookup,
      SessionCredentialPort sessionCredentials,
      AuthenticationTenantSelectionPort tenantSelection,
      TransactionRunner transactions,
      Clock clock) {
    this.mfa = mfa;
    this.cryptography = cryptography;
    this.quota = quota;
    this.authentication = authentication;
    this.refreshLookup = refreshLookup;
    this.sessionCredentials = sessionCredentials;
    this.tenantSelection = tenantSelection;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Override
  public MfaStatus status(GetMfaStatusCommand command) {
    if (command == null || command.requestId() == null || command.refreshCredential() == null) {
      throw invalid();
    }
    Instant now = clock.instant();
    LockedRefreshCredential session =
        refreshLookup
            .find(authentication, command.refreshCredential())
            .orElseThrow(MfaUseCase::invalidSession);
    requireUsableSession(session, now, false);
    return mfa.status(session.userId());
  }

  @Override
  public TotpEnrollmentStart startEnrollment(StartTotpEnrollmentCommand command) {
    if (command == null
        || command.requestId() == null
        || command.refreshCredential() == null
        || !validAddress(command.clientAddress())) throw invalid();
    Instant now = clock.instant();
    LockedRefreshCredential observed = observedRecentSession(command.refreshCredential(), now);
    quota.consume(MfaQuotaOperation.ENROLL, observed.userId(), command.clientAddress());
    UUID pendingId = UUID.randomUUID();
    GeneratedTotpSecret secret = cryptography.generateTotpSecret(observed.userId(), pendingId);
    GeneratedMfaChallenge challenge = cryptography.generateChallenge();
    StartOutcome outcome =
        transactions.required(
            () -> {
              LockedRefreshCredential locked =
                  lockSameSession(command.refreshCredential(), observed);
              requireUsableSession(locked, now, true);
              MfaStore.ActiveEnrollment active =
                  mfa.lockActiveEnrollment(locked.userId()).orElse(null);
              Instant proofAt = null;
              if (active == null && command.currentProof() != null) return StartOutcome.INVALID;
              if (active != null) {
                if (command.currentProof() == null
                    || !acceptProof(active, command.currentProof(), now)) {
                  return StartOutcome.INVALID_PROOF;
                }
                proofAt = now;
              }
              mfa.replacePendingEnrollment(
                  new MfaStore.PreparedPendingEnrollment(
                      pendingId,
                      locked.userId(),
                      active == null ? null : active.enrollmentId(),
                      challenge,
                      secret.encrypted(),
                      proofAt,
                      now.plus(ENROLLMENT_LIFETIME),
                      now),
                  now);
              return StartOutcome.ACCEPTED;
            });
    if (outcome == StartOutcome.INVALID) throw invalid();
    if (outcome == StartOutcome.INVALID_PROOF) throw invalidProof();
    return new TotpEnrollmentStart(
        challenge.encoded(), secret.base32(), secret.otpauthUri(), now.plus(ENROLLMENT_LIFETIME));
  }

  @Override
  public MfaSessionMutation confirmEnrollment(ConfirmTotpEnrollmentCommand command) {
    if (command == null
        || command.requestId() == null
        || command.refreshCredential() == null
        || command.enrollmentChallenge() == null
        || command.totpCode() == null
        || !command.totpCode().matches("[0-9]{6}")
        || !validAddress(command.clientAddress())) throw invalid();
    Instant now = clock.instant();
    LockedRefreshCredential observed = observedRecentSession(command.refreshCredential(), now);
    quota.consume(MfaQuotaOperation.ENROLL, observed.userId(), command.clientAddress());
    List<MfaDigest> digests = cryptography.challengeDigestCandidates(command.enrollmentChallenge());
    if (digests.isEmpty()) throw invalidProof();
    GeneratedRefreshCredential rotated = sessionCredentials.newRefreshCredential();
    ConfirmOutcome outcome =
        transactions.required(
            () -> {
              LockedRefreshCredential locked =
                  lockSameSession(command.refreshCredential(), observed);
              requireUsableSession(locked, now, true);
              MfaStore.PendingEnrollment pending = mfa.lockPendingEnrollment(digests).orElse(null);
              if (pending == null
                  || !pending.userId().equals(locked.userId())
                  || !"ACTIVE".equals(pending.state())) return ConfirmOutcome.invalid();
              if (!now.isBefore(pending.expiresAt())) return ConfirmOutcome.expired();
              if (pending.failedAttempts() >= 5) return ConfirmOutcome.exhausted();
              if (pending.replacesEnrollmentId() != null
                  && (pending.currentProofVerifiedAt() == null
                      || pending.currentProofVerifiedAt().plus(RECENT_AUTH).isBefore(now))) {
                return ConfirmOutcome.expired();
              }
              OptionalLong timestep =
                  cryptography.verifyTotp(
                      pending.userId(),
                      pending.pendingEnrollmentId(),
                      pending.secret(),
                      command.totpCode(),
                      now);
              if (timestep.isEmpty()) {
                mfa.recordPendingFailure(
                    pending.pendingEnrollmentId(), Math.min(5, pending.failedAttempts() + 1), now);
                return ConfirmOutcome.invalid();
              }
              List<GeneratedRecoveryCode> recoveryCodes =
                  cryptography.generateRecoveryCodes(pending.pendingEnrollmentId());
              mfa.confirmEnrollment(
                  pending, pending.pendingEnrollmentId(), timestep.getAsLong(), recoveryCodes, now);
              AuthenticationSession session = rotateCurrentSession(locked, rotated, now);
              return ConfirmOutcome.applied(
                  new MfaSessionMutation(
                      session,
                      recoveryCodes.stream().map(GeneratedRecoveryCode::encoded).toList()));
            });
    return switch (outcome.kind()) {
      case APPLIED -> outcome.mutation();
      case EXPIRED -> throw expired();
      case EXHAUSTED -> throw exhausted();
      case INVALID -> throw invalidProof();
    };
  }

  @Override
  public MfaSessionMutation disable(DisableTotpCommand command) {
    if (command == null) throw invalid();
    validateProofMutation(
        command.requestId(), command.refreshCredential(), command.proof(), command.clientAddress());
    Instant now = clock.instant();
    LockedRefreshCredential observed = observedRecentSession(command.refreshCredential(), now);
    quota.consume(MfaQuotaOperation.DISABLE, observed.userId(), command.clientAddress());
    GeneratedRefreshCredential rotated = sessionCredentials.newRefreshCredential();
    MfaSessionMutation mutation =
        transactions.required(
            () -> {
              LockedRefreshCredential locked =
                  lockSameSession(command.refreshCredential(), observed);
              requireUsableSession(locked, now, true);
              MfaStore.ActiveEnrollment active =
                  mfa.lockActiveEnrollment(locked.userId()).orElse(null);
              if (active == null) throw notEnabled();
              if (!acceptProof(active, command.proof(), now)) return null;
              mfa.disableEnrollment(active.enrollmentId(), now);
              return MfaSessionMutation.sessionOnly(rotateCurrentSession(locked, rotated, now));
            });
    if (mutation == null) throw invalidProof();
    return mutation;
  }

  @Override
  public MfaSessionMutation rotateRecoveryCodes(RotateRecoveryCodesCommand command) {
    if (command == null) throw invalid();
    validateProofMutation(
        command.requestId(), command.refreshCredential(), command.proof(), command.clientAddress());
    Instant now = clock.instant();
    LockedRefreshCredential observed = observedRecentSession(command.refreshCredential(), now);
    quota.consumeRecoverySource(command.clientAddress());
    GeneratedRefreshCredential rotated = sessionCredentials.newRefreshCredential();
    MfaSessionMutation mutation =
        transactions.required(
            () -> {
              LockedRefreshCredential locked =
                  lockSameSession(command.refreshCredential(), observed);
              requireUsableSession(locked, now, true);
              MfaStore.ActiveEnrollment active =
                  mfa.lockActiveEnrollment(locked.userId()).orElse(null);
              if (active == null) throw notEnabled();
              if (!acceptProof(active, command.proof(), now)) return null;
              List<GeneratedRecoveryCode> codes =
                  cryptography.generateRecoveryCodes(active.enrollmentId());
              mfa.replaceRecoveryCodes(locked.userId(), active.enrollmentId(), codes, now);
              return new MfaSessionMutation(
                  rotateCurrentSession(locked, rotated, now),
                  codes.stream().map(GeneratedRecoveryCode::encoded).toList());
            });
    if (mutation == null) {
      quota.recordRecoveryFailure(observed.userId());
      throw invalidProof();
    }
    return mutation;
  }

  @Override
  public AuthenticationSession complete(CompleteMfaAuthenticationCommand command) {
    if (command == null
        || command.requestId() == null
        || command.challenge() == null
        || command.proof() == null
        || !validAddress(command.clientAddress())) throw invalid();
    Instant now = clock.instant();
    quota.consumeRecoverySource(command.clientAddress());
    List<MfaDigest> digests = cryptography.challengeDigestCandidates(command.challenge());
    MfaStore.LoginChallenge observed =
        mfa.findLoginChallenge(digests).orElseThrow(MfaUseCase::invalidProof);
    requireUsableChallenge(observed, now);
    AuthenticationTenantSelection selection =
        transactions.required(
            () -> tenantSelection.resolveAfterPrimaryAuthentication(observed.userId()));
    GeneratedRefreshCredential refresh = sessionCredentials.newRefreshCredential();
    PreparedSession prepared =
        new PreparedSession(
            UUID.randomUUID(),
            sessionCredentials.newSessionId(),
            observed.userId(),
            UUID.randomUUID(),
            refresh.digest(),
            observed.primaryAuthenticatedAt(),
            now,
            now.plus(IDLE_LIFETIME),
            now.plus(ABSOLUTE_LIFETIME),
            selection.mode(),
            selection.tenantId(),
            selection.membershipId(),
            now,
            observed.authenticationMethod());
    CompleteOutcome outcome =
        transactions.required(
            () -> {
              MfaStore.LoginChallenge locked = mfa.lockLoginChallenge(digests).orElse(null);
              if (locked == null || !sameChallenge(observed, locked))
                return CompleteOutcome.INVALID;
              if (!"ACTIVE".equals(locked.state())) return CompleteOutcome.INVALID;
              if (!now.isBefore(locked.expiresAt())) return CompleteOutcome.EXPIRED;
              if (locked.failedAttempts() >= 5) return CompleteOutcome.EXHAUSTED;
              String userStatus = authentication.lockUserStatus(locked.userId()).orElse(null);
              if (!"ACTIVE".equals(userStatus) && !"PENDING".equals(userStatus))
                return CompleteOutcome.INVALID;
              MfaStore.ActiveEnrollment active =
                  mfa.lockActiveEnrollment(locked.userId()).orElse(null);
              if (active == null || !acceptProof(active, command.proof(), now)) {
                mfa.recordLoginFailure(
                    locked.challengeId(), Math.min(5, locked.failedAttempts() + 1), now);
                return CompleteOutcome.INVALID;
              }
              authentication.expireDueFamilies(locked.userId(), now);
              enforceFamilyLimit(locked.userId(), now);
              authentication.createSession(prepared);
              mfa.completeLoginChallenge(locked.challengeId(), now);
              return CompleteOutcome.APPLIED;
            });
    return switch (outcome) {
      case APPLIED -> session(prepared, refresh.encoded());
      case EXPIRED -> throw expired();
      case EXHAUSTED -> throw exhausted();
      case INVALID -> {
        quota.recordRecoveryFailure(observed.userId());
        throw invalidProof();
      }
    };
  }

  private AuthenticationSession rotateCurrentSession(
      LockedRefreshCredential locked, GeneratedRefreshCredential rotated, Instant now) {
    Instant nextIdle = min(now.plus(IDLE_LIFETIME), locked.absoluteExpiresAt());
    if (!now.isBefore(nextIdle)) throw invalidSession();
    authentication.markMfaAuthenticated(locked.refreshFamilyId(), now);
    authentication.rotateRefresh(locked, UUID.randomUUID(), rotated.digest(), now, nextIdle);
    authentication.revokeOtherFamilies(
        locked.userId(), locked.refreshFamilyId(), RefreshFamilyRevocationReason.MFA_CHANGED, now);
    return new AuthenticationSession(
        locked.sessionId(),
        locked.refreshFamilyId(),
        locked.userId(),
        rotated.encoded(),
        nextIdle,
        locked.absoluteExpiresAt(),
        locked.sessionMode(),
        locked.selectedTenantId(),
        locked.selectedMembershipId());
  }

  private boolean acceptProof(MfaStore.ActiveEnrollment active, MfaProof proof, Instant now) {
    if (proof == null) return false;
    if (proof.type() == MfaProofType.TOTP) {
      OptionalLong timestep =
          cryptography.verifyTotp(
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
          cryptography.recoveryDigestCandidates(active.enrollmentId(), proof.code()),
          now);
    }
    return false;
  }

  private LockedRefreshCredential observedRecentSession(String refreshCredential, Instant now) {
    LockedRefreshCredential observed =
        refreshLookup
            .find(authentication, refreshCredential)
            .orElseThrow(MfaUseCase::invalidSession);
    requireUsableSession(observed, now, true);
    return observed;
  }

  private LockedRefreshCredential lockSameSession(
      String refreshCredential, LockedRefreshCredential observed) {
    LockedRefreshCredential locked =
        refreshLookup
            .lock(authentication, refreshCredential)
            .orElseThrow(MfaUseCase::invalidSession);
    if (!locked.userId().equals(observed.userId())
        || !locked.refreshFamilyId().equals(observed.refreshFamilyId())
        || !locked.credentialId().equals(observed.credentialId())) throw invalidSession();
    return locked;
  }

  private void enforceFamilyLimit(UUID userId, Instant now) {
    int active = authentication.countActiveFamilies(userId);
    if (active < 0 || active > ACTIVE_FAMILY_LIMIT) {
      throw new AuthenticationException(
          AuthenticationError.SESSION_STATE_INVALID, "Session family state is invalid");
    }
    if (active == ACTIVE_FAMILY_LIMIT) {
      UUID oldest =
          authentication
              .oldestActiveFamily(userId)
              .orElseThrow(
                  () ->
                      new AuthenticationException(
                          AuthenticationError.SESSION_STATE_INVALID,
                          "Session family state is invalid"));
      authentication.revokeFamily(oldest, RefreshFamilyRevocationReason.ACTIVE_FAMILY_LIMIT, now);
    }
  }

  private static void requireUsableSession(
      LockedRefreshCredential session, Instant now, boolean requireRecent) {
    if (!"ACTIVE".equals(session.credentialState())
        || !"ACTIVE".equals(session.familyState())
        || !"ACTIVE".equals(session.userStatus())
        || !now.isBefore(session.idleExpiresAt())
        || !now.isBefore(session.absoluteExpiresAt())) throw invalidSession();
    if (requireRecent && session.authenticatedAt().plus(RECENT_AUTH).isBefore(now)) {
      throw new MfaException(
          MfaError.RECENT_AUTHENTICATION_REQUIRED, "Recent authentication is required");
    }
  }

  private static void requireUsableChallenge(MfaStore.LoginChallenge challenge, Instant now) {
    if (!"ACTIVE".equals(challenge.state())) throw invalidProof();
    if (!now.isBefore(challenge.expiresAt())) throw expired();
    if (challenge.failedAttempts() >= 5) throw exhausted();
  }

  private static boolean sameChallenge(
      MfaStore.LoginChallenge observed, MfaStore.LoginChallenge locked) {
    return observed.challengeId().equals(locked.challengeId())
        && observed.userId().equals(locked.userId())
        && observed.primaryAuthenticatedAt().equals(locked.primaryAuthenticatedAt());
  }

  private static AuthenticationSession session(PreparedSession prepared, String refresh) {
    return new AuthenticationSession(
        prepared.sessionId(),
        prepared.refreshFamilyId(),
        prepared.userId(),
        refresh,
        prepared.idleExpiresAt(),
        prepared.absoluteExpiresAt(),
        prepared.mode(),
        prepared.selectedTenantId(),
        prepared.selectedMembershipId());
  }

  private static void validateProofMutation(
      UUID requestId, String refreshCredential, MfaProof proof, byte[] address) {
    if (requestId == null || refreshCredential == null || proof == null || !validAddress(address))
      throw invalid();
  }

  private static boolean validAddress(byte[] address) {
    return address != null && (address.length == 4 || address.length == 16);
  }

  private static Instant min(Instant left, Instant right) {
    return left.isBefore(right) ? left : right;
  }

  private static MfaException invalid() {
    return new MfaException(MfaError.INVALID_ARGUMENT, "MFA request is invalid");
  }

  private static MfaException invalidSession() {
    return new MfaException(MfaError.INVALID_SESSION, "MFA session is invalid");
  }

  private static MfaException invalidProof() {
    return new MfaException(MfaError.INVALID_PROOF, "MFA proof is invalid");
  }

  private static MfaException expired() {
    return new MfaException(MfaError.CHALLENGE_EXPIRED, "MFA challenge has expired");
  }

  private static MfaException exhausted() {
    return new MfaException(MfaError.CHALLENGE_EXHAUSTED, "MFA challenge is exhausted");
  }

  private static MfaException notEnabled() {
    return new MfaException(MfaError.MFA_NOT_ENABLED, "MFA is not enabled");
  }

  private enum StartOutcome {
    ACCEPTED,
    INVALID,
    INVALID_PROOF
  }

  private enum CompleteOutcome {
    APPLIED,
    INVALID,
    EXPIRED,
    EXHAUSTED
  }

  private enum ConfirmKind {
    APPLIED,
    INVALID,
    EXPIRED,
    EXHAUSTED
  }

  private record ConfirmOutcome(ConfirmKind kind, MfaSessionMutation mutation) {
    static ConfirmOutcome applied(MfaSessionMutation mutation) {
      return new ConfirmOutcome(ConfirmKind.APPLIED, mutation);
    }

    static ConfirmOutcome invalid() {
      return new ConfirmOutcome(ConfirmKind.INVALID, null);
    }

    static ConfirmOutcome expired() {
      return new ConfirmOutcome(ConfirmKind.EXPIRED, null);
    }

    static ConfirmOutcome exhausted() {
      return new ConfirmOutcome(ConfirmKind.EXHAUSTED, null);
    }
  }
}
