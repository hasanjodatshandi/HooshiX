package com.sajtech.identity.domain.registration;

public enum RegistrationLocale {
  FA("fa"),
  EN("en");

  private final String wireValue;

  RegistrationLocale(String wireValue) {
    this.wireValue = wireValue;
  }

  public String wireValue() {
    return wireValue;
  }
}
