package com.sajtech.identity.application.authentication.model;

public enum RefreshFamilyRevocationReason {
  ACTIVE_FAMILY_LIMIT,
  LOGOUT_CURRENT,
  LOGOUT_ALL,
  REFRESH_REUSE,
  EXPIRED,
  USER_INACTIVE,
  PASSWORD_CHANGED,
  MFA_CHANGED,
  EXTERNAL_IDENTITY_CHANGED
}
