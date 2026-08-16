package com.sajtech.compromisedpassword.application.lookup;

public final class LookupOverloadedException extends RuntimeException {
  public LookupOverloadedException() {
    super("Lookup capacity is exhausted");
  }
}
