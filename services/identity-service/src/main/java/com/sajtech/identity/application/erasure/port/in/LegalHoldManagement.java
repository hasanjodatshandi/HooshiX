package com.sajtech.identity.application.erasure.port.in;

import com.sajtech.identity.application.erasure.model.LegalHoldView;
import com.sajtech.identity.application.mfa.model.MfaProof;
import java.util.UUID;

public interface LegalHoldManagement {
  LegalHoldView create(
      UUID requestId,
      String refreshCredential,
      UUID erasureRequestId,
      String authorityReference,
      MfaProof proof);

  LegalHoldView release(UUID requestId, String refreshCredential, UUID holdId, MfaProof proof);
}
