package com.sajtech.identity.application.authentication.port.out;

import com.sajtech.identity.application.authentication.model.AccessTokenContext;
import com.sajtech.identity.application.authentication.model.SignedAccessToken;

public interface AccessTokenSigner {
  SignedAccessToken sign(AccessTokenContext context);
}
