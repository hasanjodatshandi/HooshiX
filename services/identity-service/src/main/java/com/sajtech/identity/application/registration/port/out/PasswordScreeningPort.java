package com.sajtech.identity.application.registration.port.out;

public interface PasswordScreeningPort {
  void requireNotCompromised(String normalizedPassword);
}
