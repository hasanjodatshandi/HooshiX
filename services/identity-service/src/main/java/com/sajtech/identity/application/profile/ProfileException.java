package com.sajtech.identity.application.profile;

public final class ProfileException extends RuntimeException {
  private final ProfileError error;

  public ProfileException(ProfileError error, String message) {
    super(message);
    this.error = error;
  }

  public ProfileError error() {
    return error;
  }
}
