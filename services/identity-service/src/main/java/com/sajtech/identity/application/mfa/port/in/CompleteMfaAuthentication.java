package com.sajtech.identity.application.mfa.port.in;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;

public interface CompleteMfaAuthentication {
  AuthenticationSession complete(CompleteMfaAuthenticationCommand command);
}
