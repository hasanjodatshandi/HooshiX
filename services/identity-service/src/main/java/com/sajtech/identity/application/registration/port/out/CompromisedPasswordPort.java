package com.sajtech.identity.application.registration.port.out;

public interface CompromisedPasswordPort {
  void requireNotCompromised(String normalizedPassword);
}
