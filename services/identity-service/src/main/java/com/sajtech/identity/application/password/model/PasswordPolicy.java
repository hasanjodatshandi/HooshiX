package com.sajtech.identity.application.password.model;

public final class PasswordPolicy {
  private PasswordPolicy() {}

  public static void validate(String password) {
    int length = password.codePointCount(0, password.length());
    if (length < 12 || length > 128) {
      throw new IllegalArgumentException("invalid password policy");
    }
  }
}
