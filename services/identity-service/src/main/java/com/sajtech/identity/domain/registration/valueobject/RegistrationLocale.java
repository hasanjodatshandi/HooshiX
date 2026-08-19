package com.sajtech.identity.domain.registration.valueobject;

public enum RegistrationLocale {
  FA("fa"),
  EN("en");

  private final String canonical;

  RegistrationLocale(String canonical) {
    this.canonical = canonical;
  }

  public String canonical() {
    return canonical;
  }
}
