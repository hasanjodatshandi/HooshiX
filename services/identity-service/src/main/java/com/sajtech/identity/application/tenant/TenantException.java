package com.sajtech.identity.application.tenant;

public final class TenantException extends RuntimeException {
  private final TenantError error;

  public TenantException(TenantError error, String message) {
    super(message);
    this.error = error;
  }

  public TenantException(TenantError error, String message, Throwable cause) {
    super(message, cause);
    this.error = error;
  }

  public TenantError error() {
    return error;
  }
}
