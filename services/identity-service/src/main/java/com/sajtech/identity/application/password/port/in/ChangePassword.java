package com.sajtech.identity.application.password.port.in;

public interface ChangePassword {
  PasswordChangeSession change(ChangePasswordCommand command);
}
