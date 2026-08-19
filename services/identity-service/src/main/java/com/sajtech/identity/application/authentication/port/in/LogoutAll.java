package com.sajtech.identity.application.authentication.port.in;

import com.sajtech.identity.application.authentication.model.LogoutAllCommand;

public interface LogoutAll {
  void logoutAll(LogoutAllCommand command);
}
