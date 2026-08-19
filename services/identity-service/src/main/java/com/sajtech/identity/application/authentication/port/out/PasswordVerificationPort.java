package com.sajtech.identity.application.authentication.port.out;

public interface PasswordVerificationPort {
  boolean matches(String normalizedPassword, String encodedHash);
}
