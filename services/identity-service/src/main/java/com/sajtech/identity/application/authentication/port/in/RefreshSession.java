package com.sajtech.identity.application.authentication.port.in;

import com.sajtech.identity.application.authentication.model.AuthenticationSession;
import com.sajtech.identity.application.authentication.model.RefreshSessionCommand;

public interface RefreshSession {
  AuthenticationSession refresh(RefreshSessionCommand command);
}
