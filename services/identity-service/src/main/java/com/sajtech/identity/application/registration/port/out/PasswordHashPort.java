package com.sajtech.identity.application.registration.port.out;

public interface PasswordHashPort {
  String hash(String normalizedPassword);
}
