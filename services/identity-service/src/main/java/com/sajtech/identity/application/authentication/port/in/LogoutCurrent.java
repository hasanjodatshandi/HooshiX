package com.sajtech.identity.application.authentication.port.in;

import com.sajtech.identity.application.authentication.model.LogoutCurrentCommand;

public interface LogoutCurrent {
  void logout(LogoutCurrentCommand command);
}
