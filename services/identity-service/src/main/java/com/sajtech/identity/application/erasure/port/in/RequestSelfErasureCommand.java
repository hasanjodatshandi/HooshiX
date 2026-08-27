package com.sajtech.identity.application.erasure.port.in;

import com.sajtech.identity.application.mfa.model.MfaProof;
import java.util.UUID;

public record RequestSelfErasureCommand(
    UUID requestId, String refreshCredential, MfaProof mfaProof, String confirmation) {
  public RequestSelfErasureCommand {
    if (requestId == null || refreshCredential == null || confirmation == null) {
      throw new IllegalArgumentException("Self-erasure command is invalid");
    }
  }
}
