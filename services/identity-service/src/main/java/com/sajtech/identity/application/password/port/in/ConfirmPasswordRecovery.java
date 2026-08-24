package com.sajtech.identity.application.password.port.in;

public interface ConfirmPasswordRecovery {
  void confirm(ConfirmPasswordRecoveryCommand command);
}
