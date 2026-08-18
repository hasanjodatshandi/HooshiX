package com.sajtech.identity.application.registration.port.in;

import com.sajtech.identity.application.registration.model.ResendRegistrationCommand;

public interface ResendRegistrationVerification {
  void resend(ResendRegistrationCommand command);
}
