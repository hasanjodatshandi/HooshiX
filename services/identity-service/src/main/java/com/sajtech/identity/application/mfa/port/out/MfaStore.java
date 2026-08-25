package com.sajtech.identity.application.mfa.port.out;

import com.sajtech.identity.application.mfa.model.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MfaStore extends MfaAuthenticationGate {
  MfaStatus status(UUID userId);

  Optional<ActiveEnrollment> lockActiveEnrollment(UUID userId);

  void replacePendingEnrollment(PreparedPendingEnrollment pending, Instant now);

  Optional<PendingEnrollment> lockPendingEnrollment(List<MfaDigest> challengeDigests);

  void recordPendingFailure(UUID pendingEnrollmentId, int failedAttempts, Instant now);

  void confirmEnrollment(
      PendingEnrollment pending,
      UUID enrollmentId,
      long acceptedTimestep,
      List<GeneratedRecoveryCode> recoveryCodes,
      Instant now);

  Optional<LoginChallenge> lockLoginChallenge(List<MfaDigest> challengeDigests);

  Optional<LoginChallenge> findLoginChallenge(List<MfaDigest> challengeDigests);

  void recordLoginFailure(UUID challengeId, int failedAttempts, Instant now);

  void completeLoginChallenge(UUID challengeId, Instant now);

  void acceptTotp(UUID enrollmentId, long acceptedTimestep, Instant now);

  boolean consumeRecoveryCode(
      UUID userId, UUID enrollmentId, List<MfaDigest> digestCandidates, Instant now);

  void disableEnrollment(UUID enrollmentId, Instant now);

  void replaceRecoveryCodes(
      UUID userId, UUID enrollmentId, List<GeneratedRecoveryCode> recoveryCodes, Instant now);

  int deletePendingEnrollmentsBefore(Instant cutoff, int batch);

  int deleteLoginChallengesBefore(Instant cutoff, int batch);

  record ActiveEnrollment(
      UUID enrollmentId, UUID userId, EncryptedTotpSecret secret, Long lastAcceptedTimestep) {}

  record PreparedPendingEnrollment(
      UUID pendingEnrollmentId,
      UUID userId,
      UUID replacesEnrollmentId,
      GeneratedMfaChallenge challenge,
      EncryptedTotpSecret secret,
      Instant currentProofVerifiedAt,
      Instant expiresAt,
      Instant createdAt) {}

  record PendingEnrollment(
      UUID pendingEnrollmentId,
      UUID userId,
      UUID replacesEnrollmentId,
      EncryptedTotpSecret secret,
      int failedAttempts,
      Instant currentProofVerifiedAt,
      Instant expiresAt,
      String state) {}

  record LoginChallenge(
      UUID challengeId,
      UUID userId,
      int failedAttempts,
      Instant primaryAuthenticatedAt,
      Instant expiresAt,
      String state) {}
}
