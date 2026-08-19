package com.sajtech.identity.application.registration.port.in;

import com.sajtech.identity.application.registration.model.ConfirmRegistrationCommand;

public interface ConfirmRegistration {
  boolean confirm(ConfirmRegistrationCommand command);
}
