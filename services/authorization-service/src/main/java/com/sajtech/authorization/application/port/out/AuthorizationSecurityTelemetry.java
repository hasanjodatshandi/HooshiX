package com.sajtech.authorization.application.port.out;

@FunctionalInterface
public interface AuthorizationSecurityTelemetry {
  void auditFailure();
}
