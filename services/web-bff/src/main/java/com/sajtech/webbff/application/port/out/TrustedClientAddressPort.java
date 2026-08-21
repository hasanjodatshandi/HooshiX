package com.sajtech.webbff.application.port.out;

public interface TrustedClientAddressPort {
  byte[] parse(String value);
}
