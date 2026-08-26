package com.sajtech.identity.application.externalidentity.port.out;

import com.sajtech.identity.application.externalidentity.model.EncryptedExternalIdentityResult;
import com.sajtech.identity.application.registration.model.FingerprintDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ExternalIdentityStore {
  void lockEvidence(byte[] evidenceId);

  void lockRequest(UUID requestId, String operation);

  Optional<ConsumedEvidence> findEvidence(byte[] evidenceId);

  Optional<ConsumedEvidence> findEvidenceByRequest(UUID requestId, String operation);

  void lockSubject(String issuer, String subject);

  void lockContactKey(String canonicalEmail);

  Optional<Binding> findActiveBinding(String issuer, String subject);

  Optional<Binding> findActiveBinding(UUID userId, String issuer);

  boolean verifiedEmailUnavailable(String canonicalEmail, UUID allowedUserId);

  UUID createExternalUser(
      String issuer,
      String subject,
      String canonicalEmail,
      String deliveryEmail,
      String givenName,
      String familyName,
      Instant now);

  void link(UUID externalIdentityId, UUID userId, String issuer, String subject, Instant now);

  void unlink(UUID externalIdentityId, Instant now);

  boolean hasLocalCredential(UUID userId);

  int activeExternalIdentityCount(UUID userId);

  void saveEvidence(
      byte[] evidenceId,
      UUID requestId,
      String operation,
      String issuer,
      String subject,
      FingerprintDigest fingerprint,
      String outcome,
      UUID userId,
      EncryptedExternalIdentityResult encryptedResult,
      Instant issuedAt,
      Instant consumedAt,
      Instant retainUntil);

  int deleteEvidenceBefore(Instant cutoff, int batch);

  record Binding(UUID externalIdentityId, UUID userId, String userStatus) {}

  record ConsumedEvidence(
      byte[] evidenceId,
      UUID requestId,
      String operation,
      String issuer,
      String subject,
      byte[] fingerprint,
      String fingerprintKeyId,
      String fingerprintVersion,
      String outcome,
      UUID userId,
      EncryptedExternalIdentityResult encryptedResult) {
    public ConsumedEvidence {
      evidenceId = evidenceId.clone();
      fingerprint = fingerprint.clone();
    }

    @Override
    public byte[] evidenceId() {
      return evidenceId.clone();
    }

    @Override
    public byte[] fingerprint() {
      return fingerprint.clone();
    }
  }
}
