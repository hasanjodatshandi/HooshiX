package com.sajtech.identity.application.mfa.model;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import java.util.List;

public record MfaSessionMutation(AuthenticationSession session, List<String> recoveryCodes) {
  public MfaSessionMutation {
    if (session == null || recoveryCodes == null) {
      throw new IllegalArgumentException("MFA session mutation is invalid");
    }
    recoveryCodes = List.copyOf(recoveryCodes);
  }

  public static MfaSessionMutation sessionOnly(AuthenticationSession session) {
    return new MfaSessionMutation(session, List.of());
  }
}
