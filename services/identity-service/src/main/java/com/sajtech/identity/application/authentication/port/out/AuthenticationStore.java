package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.application.authentication.model.*;
import com.sajtech.identity.domain.registration.valueobject.CanonicalContact;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AuthenticationStore {
  Optional<LocalCredentialRecord> findVerifiedLocalCredential(CanonicalContact contact);

  Optional<LocalCredentialRecord> lockVerifiedLocalCredential(
      UUID userId, CanonicalContact contact);

  void expireDueFamilies(UUID userId, Instant now);

  int countActiveFamilies(UUID userId);

  Optional<UUID> oldestActiveFamily(UUID userId);

  void createSession(PreparedSession session);

  Optional<LockedRefreshCredential> lockRefreshCredential(RefreshDigest digest);

  void rotateRefresh(
      LockedRefreshCredential current,
      UUID newCredentialId,
      RefreshDigest nextDigest,
      Instant now,
      Instant nextIdleExpiresAt);

  void revokeFamily(UUID refreshFamilyId, RefreshFamilyRevocationReason reason, Instant now);

  void revokeAllFamilies(UUID userId, RefreshFamilyRevocationReason reason, Instant now);

  default void updatePasswordHash(UUID userId, String passwordHash, Instant now) {
    throw new UnsupportedOperationException("Password mutation is not supported by this store");
  }

  int deleteFamiliesBefore(Instant cutoff, int batch);
}
