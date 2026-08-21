package com.sajtech.identity.application.authentication.port.in;

import com.sajtech.identity.application.authentication.model.AuthenticateLocalCommand;
import com.sajtech.identity.application.authentication.model.AuthenticationSession;

public interface AuthenticateLocal {
  AuthenticationSession authenticate(AuthenticateLocalCommand command);
}
