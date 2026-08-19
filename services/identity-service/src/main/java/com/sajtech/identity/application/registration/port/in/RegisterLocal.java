package com.sajtech.identity.application.registration.port.in;

import com.sajtech.identity.application.registration.model.RegisterLocalCommand;

public interface RegisterLocal {
  void register(RegisterLocalCommand command);
}
