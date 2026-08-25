package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticationStore {
  Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact);

  default Optional<LocalCredentialRecord> findLocalCredential(UUID userId) {
    return Optional.empty();
  }

  default Optional<LocalCredentialRecord> lockLocalCredential(UUID userId) {
    return findLocalCredential(userId);
  }

  Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
      UUID userId, CanonicalContact contact);

  default Optional<String> lockUserStatus(UUID userId) {
    return lockLocalCredential(userId).map(LocalCredentialRecord::userStatus);
  }

  void expireDueFamilies(UUID userId, Instant now);

  int countActiveFamilies(UUID userId);

  Optional<UUID> oldestActiveFamily(UUID userId);

  void createSession(PreparedSession session);

  Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest);

  default Optional<LockedRefreshCredential> findRefreshCredential(RefreshDigest digest) {
    return Optional.empty();
  }

  void rotateRefresh(
      LockedRefreshCredential current,
      UUID newCredentialId,
      RefreshDigest nextDigest,
      Instant now,
      Instant nextIdleExpiresAt);

  void revokeFamily(UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now);

  void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now);

  default void markMfaAuthenticated(UUID refreshFamilyId, Instant now) {
    throw new UnsupportedOperationException("MFA assurance mutation is not supported");
  }

  default void revokeOtherFamilies(
      UUID userId, UUID retainedFamilyId, RefreshFamilyRevocationReason reason, Instant now) {
    throw new UnsupportedOperationException("Selective family revocation is not supported");
  }

  default void updatePasswordHash(UUID userId, String passwordHash, Instant now) {
    throw new UnsupportedOperationException("Password mutation is not supported by this store");
  }

  int deleteFamiliesBefore(Instant cutoff, int batch);
}
