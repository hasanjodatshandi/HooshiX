package com.sajtech.identity.application.authentication.service;

import com.sajtech.identity.application.authentication.AuthenticationError;
import com.sajtech.identity.application.authentication.AuthenticationException;
import com.sajtech.identity.application.authentication.model.LockedRefreshCredential;
import com.sajtech.identity.application.authentication.model.RefreshDigest;
import com.sajtech.identity.application.authentication.port.out.AuthenticationStore;
import com.sajtech.identity.application.authentication.port.out.SessionCredentialPort;
import java.util.List;
import java.util.Optional;

public final class RefreshCredentialLookup {
  private static final int MAX_RETAINED_DIGEST_KEYS = 8;
  private final SessionCredentialPort credentials;

  public RefreshCredentialLookup(SessionCredentialPort credentials) {
    this.credentials = credentials;
  }

  public Optional<LockedRefreshCredential> lock(
      AuthenticationStore store, String encodedCredential) {
    List<RefreshDigest> candidates;
    try {
      candidates = credentials.digestCandidates(encodedCredential);
    } catch (IllegalArgumentException exception) {
      return Optional.empty();
    }
    if (candidates.isEmpty() || candidates.size() > MAX_RETAINED_DIGEST_KEYS) {
      throw new AuthenticationException(
          AuthenticationError.SESSION_STATE_INVALID, "Refresh verification key set is invalid");
    }
    for (RefreshDigest candidate : candidates) {
      Optional<LockedRefreshCredential> found = store.lockRefreshCredential(candidate);
      if (found.isPresent()) return found;
    }
    return Optional.empty();
  }
}
