package com.sajtech.webbff.application.port.out;

public interface OidcQuotaPort {
  void consume(Operation operation, byte[] clientAddress);

  enum Operation {
    OIDC_START,
    OIDC_CALLBACK
  }
}
